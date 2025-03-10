<template>
  <article class="group-aside" v-bkloading="{ isLoading: !groupList.length }">
    <span class="group-title">{{ t('权限角色') }}</span>
    <scroll-load-list
      class="group-list"
      ref="loadList"
      :list="groupList"
      :has-load-end="hasLoadEnd"
      :page="page"
      :get-data-method="handleGetData"
    >
      <template v-slot:default="{ data: group }">
        <div
          :class="{ 'group-item': true, 'group-active': activeTab === group.groupId }"
          @click="handleChooseGroup(group)"
        >
          <span class="group-name" :title="group.name">{{ group.name }}</span>
          <div class="num-box" v-for="item in groupCountField" :key="item">
            <i
              :class="['group-icon', 'manage-icon', {
                'manage-icon-user-shape': item === 'userCount',
                'manage-icon-user-template': item === 'templateCount',
                'manage-icon-organization': item === 'departmentCount',
                'active': activeTab === group.groupId
              }]"
            />
            <div class="group-num">{{ group[item] }}</div>
          </div>
          <bk-popover
            v-if="resourceType === 'project'"
            class="group-more-option"
            placement="bottom"
            theme="dot-menu light"
            :arrow="false"
            offset="15"
            :distance="0">
            <i @click.stop class="more-icon manage-icon manage-icon-more-fill"></i>
            <template #content>
              <bk-button
                class="btn"
                :disabled="[1, 2].includes(group.id)"
                text
                @click="handleShowDeleteGroup(group)">
                {{ t('删除') }}
              </bk-button>
            </template>
          </bk-popover>
        </div>
      </template>
    </scroll-load-list>
    <div class="line-split" />
    <div
      v-if="showCreateGroup"
      :class="{ 'group-item': true, 'group-active': activeTab === '' }"
      @click="handleCreateGroup">
      <span class="add-group-btn">
        <i class="bk-icon bk-icon-add-fill add-icon"></i>
        {{ t('新建用户组') }}
      </span>
    </div>
    <div class="close-btn">
      <bk-button @click="showCloseManageDialog" :loading="isClosing">{{ t('关闭权限管理') }}</bk-button>
    </div>
    <bk-dialog
      header-align="center"
      theme="danger"
      ext-cls="close-manage-dialog"
      width="500"
      :quick-close="false"
      :show-footer="false"
      :value="closeObj.isShow"
      @cancel="handleHiddenCloseManage"
    >
      <template #header>
        <i class="warning-icon header-warning-icon manage-icon manage-icon-more-fill"></i>
        <p class="close-title">{{ t('确认关闭【】的权限管理？', [resourceName]) }}</p>
      </template>
      <template>
        <div class="close-tips">
          <p>{{ t('关闭流水线权限管理，将执行如下操作：', [resourceTypeName]) }}</p>
          <p>
            <i class="warning-icon manage-icon manage-icon-more-fill"></i>
            {{ closeManageTips || t('将编辑者、执行者、查看者中的用户移除') }}
          </p>
          <p>
            <i class="warning-icon manage-icon manage-icon-more-fill"></i>
            {{ t('删除对应组内用户继承该组的权限') }}
          </p>
          <p>
            <i class="warning-icon manage-icon manage-icon-more-fill"></i>
            {{ t('删除对应组信息和组权限') }}
          </p>
        </div>
      </template>
      <div class="confirm-close">
        <span style="color: #737987; font-size: 14px;">
          {{ t('提交后，再次开启权限管理时对应组内用户将不能恢复,请谨慎操作!') }}
        </span>
      </div>
      <div class="option-btns">
        <bk-button
          class="close-btn"
          theme="danger"
          @click="handleCloseManage"
        >
          {{ t('关闭权限管理') }}
        </bk-button>
        <bk-button
          class="btn"
          @click="handleHiddenCloseManage"
        >
          {{ t('取消') }}
        </bk-button>
      </div>
    </bk-dialog>
  </article>
</template>

<script>
import ScrollLoadList from '../../widget-components/scroll-load-list';
import ajax from '../../../ajax/index';
import { localeMixins } from '../../../utils/locale'

export default {
  components: {
    ScrollLoadList,
  },
  mixins: [localeMixins],
  props: {
    activeIndex: {
      type: Boolean,
      default: 0,
    },
    resourceType: {
      type: String,
      default: '',
    },
    resourceCode: {
      type: String,
      default: '',
    },
    resourceName: {
      type: String,
      default: '',
    },
    projectCode: {
      type: String,
      default: '',
    },
    showCreateGroup: {
      type: Boolean,
      default: true,
    },
    ajaxPrefix: {
      type: String,
      default: '',
    },
  },
  emits: ['choose-group', 'create-group', 'close-manage'],
  data() {
    return {
      page: 1,
      activeTab: '',
      deleteObj: {
        group: {},
        isShow: false,
        isLoading: false,
      },
      closeObj: {
        isShow: false,
        isLoading: false
      },
      groupList: [],
      hasLoadEnd: false,
      isClosing: false,
      curGroupIndex: -1,
    };
  },
  computed: {
    groupCountField () {
      if (this.resourceType === 'pipeline') {
        return ['userCount', 'templateCount', 'departmentCount']
      }
      return ['userCount', 'departmentCount']
    },
    resourceTypeName () {
      const nameMap = {
        'pipeline': this.t('流水线'),
        'pipeline_group': this.t('流水线组'),
        'pipeline_template': this.t('流水线模板'),
        'environment': this.t('环境')
      };
      return nameMap[this.resourceType] || this.resourceType;
    },
    closeManageTips () {
      const tipsMap = {
        'pipeline': this.t('将编辑者、执行者、查看者中的用户移除'),
        'pipeline_group': this.t('将编辑者、执行者、查看者中的用户移除'),
        'pipeline_template': this.t('将编辑者中的用户移除'),
        'environment': this.t('将拥有者、使用者组中的用户移除')
      }
      return tipsMap[this.resourceType];
    }
  },
  watch: {
    activeIndex(newVal) {
      this.activeTab = this.groupList[newVal]?.groupId || '';
    },
  },
  async created() {
    window.addEventListener('message', this.handleMessage);
  },

  beforeUnmount() {
    window.removeEventListener('message', this.handleMessage);
  },
  methods: {
    handleGetData(pageSize) {
      return ajax
        .get(`${this.ajaxPrefix}/auth/api/user/auth/resource/${this.projectCode}/${this.resourceType}/${this.resourceCode}/listGroup?page=${this.page}&pageSize=${pageSize}`)
        .then(({ data }) => {
          this.hasLoadEnd = !data.hasNext;
          this.groupList.push(...data.records);
          // 首页需要加载
          if (this.page === 1) {
            const chooseGroup = this.groupList.find(group => +group.groupId === +this.$route.query?.groupId) || this.groupList[0];
            this.handleChooseGroup(chooseGroup);
          }
          this.page += 1
        });
    },
    refreshList() {
      this.groupList = [];
      this.hasLoadEnd = false;
      this.page = 1;
      return this.handleGetData(100)
    },
    handleShowDeleteGroup(group) {
      this.deleteObj.group = group;
      this.deleteObj.isShow = true;
    },
    handleHiddenDeleteGroup() {
      this.deleteObj.isShow = false;
      this.deleteObj.group = {};
    },
    handleDeleteGroup() {
      this.deleteObj.isLoading = true;
      return ajax
        .delete(`${this.ajaxPrefix}/auth/api/user/auth/resource/group/${this.projectCode}/${this.resourceType}/${this.deleteObj.group.groupId}`)
        .then(() => {
          this.handleHiddenDeleteGroup();
          this.refreshList();
        })
        .finally(() => {
          this.deleteObj.isLoading = false;
        });
    },
    handleChooseGroup(group) {
      this.$router.replace({
        query: {
          groupId: group.groupId
        }
      })
      this.activeTab = group.groupId;
      this.curGroupIndex = this.groupList.findIndex(item => item.groupId === group.groupId);
      this.$emit('choose-group', group);
    },
    handleCreateGroup() {
      this.activeTab = '';
      this.$emit('create-group');
    },
    handleCloseManage() {
      this.isClosing = true;
      return ajax
        .put(`${this.ajaxPrefix}/auth/api/user/auth/resource/${this.projectCode}/${this.resourceType}/${this.resourceCode}/disable`)
        .then(() => {
          this.$emit('close-manage');
        })
        .finally(() => {
          this.isClosing = false;
        });
    },
    showCloseManageDialog () {
      this.closeObj.isShow = true
    },
    handleHiddenCloseManage () {
      this.closeObj.isShow = false
    },
    handleMessage(event) {
      const { data } = event;
      if (data.type === 'IAM') {
        switch (data.code) {
          case 'create_user_group_submit':
            this
              .refreshList()
              .then(() => {
                const group = this.groupList.find(group => group.groupId === data?.data?.id) || this.groupList[0];
                this.handleChooseGroup(group);
              })
            break;
          case 'create_user_group_cancel':
            this.handleChooseGroup(this.groupList[0]);
            break;
          case 'add_user_confirm':
          case 'add_template_confirm':
            this.groupList[this.curGroupIndex].departmentCount += data.data.departments.length
            this.groupList[this.curGroupIndex].userCount += data.data.users.length
            this.groupList[this.curGroupIndex].templateCount += data.data.templates.length
            this.syncGroupIAM(this.groupList[this.curGroupIndex].groupId)
            break;
          case 'remove_user_confirm':
          case 'remove_template_confirm': {
            const departments = data.data.members.filter(i => i.type === 'department')
            const users = data.data.members.filter(i => i.type === 'user')
            const templates = data.data.members.filter(i => i.type === 'template')
            this.groupList[this.curGroupIndex].departmentCount -= departments.length
            this.groupList[this.curGroupIndex].userCount -= users.length
            this.groupList[this.curGroupIndex].templateCount -= templates.length
            this.syncGroupIAM(this.groupList[this.curGroupIndex].groupId)
            break;
          }
          case 'change_group_detail_tab':
            this.$emit('change-group-detail-tab', data.data.tab)
            break;
          case 'submit_add_group_perm':
          case 'submit_delete_group_perm':
          case 'submit_edit_group_perm': {
            const groupId = data.data.id;
            this.syncGroupPermissions(groupId)
            break;
          }
          case 'renewal_user_confirm':
          case 'renewal_template_confirm':  {
            const groupId = data.data.id;
            this.syncGroupIAM(this.groupList[this.curGroupIndex].groupId)
          }
        }
      }
    },
    async syncGroupPermissions(groupId){
      try {
        await ajax.put(`${this.ajaxPrefix}/auth/api/user/auth/resource/group/sync/${this.projectCode}/${groupId}/syncGroupPermissions`);
      } catch (error) {
        Message({
          theme: 'error',
          message: error.message
        });
      }
    },
    async syncGroupIAM(groupId){
      try {
        await ajax.put(`${this.ajaxPrefix}/auth/api/user/auth/resource/group/sync/${this.projectCode}/${groupId}/syncGroupMember`);
      } catch (error) {
        Message({
          theme: 'error',
          message: error.message
        });
      }
    },
  },
};
</script>

<style lang="scss" scoped>
.group-aside {
  min-width: 240px;
  width: 240px;
  height: 100%;
  background-color: #fff;
  border-right: 1px solid #dde0e6;
}
.group-list {
  max-height: calc(100% - 70px);
  height: auto;
  overflow-y: auto;
  &::-webkit-scrollbar-thumb {
    background-color: #c4c6cc !important;
    border-radius: 5px !important;
    &:hover {
      background-color: #979ba5 !important;
    }
  }
  &::-webkit-scrollbar {
    width: 4px !important;
    height: 4px !important;
  }
}
.group-title {
  display: inline-block;
  line-height: 50px;
  padding-left: 24px;
  width: 100%;
  font-size: 14px;
  margin-bottom: 8px;
  font-weight: bold;
}
.group-item {
  display: flex;
  align-items: center;
  width: 100%;
  height: 40px;
  line-height: 40px;
  font-size: 14px;
  padding: 0 12px;
  color: #63656E;
  cursor: pointer;
  &:hover {
    background-color: #eaebf0;
  }
}
.group-active {
  color: #3A84FF !important;
  background-color: #E1ECFF !important;
  .user-num, .group-num {
    background-color: #A3C5FD;
    color: #fff;
  }
  .group-icon {
    color: #A3C5FD;
  }
}
.num-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding-right: 10px;
}
.user-num,
.group-num {
  background-color: #A3C5FD;
  color: #fff;
}
.group-name {
  display: inline-block;
  flex: 1;
  max-width: 130px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.user-num,
.group-num {
  display: flex;
  align-items: center;
  justify-content: space-evenly;
  width: 40px;
  height: 16px;
  background: #F0F1F5;
  border-radius: 2px;
  font-size: 12px;
  line-height: 16px;
  margin-right: 3px;
  text-align: center;
  color: #C4C6CC;
}
.more-icon {
  border-radius: 50%;
  color: #63656e;
  padding: 1px;
}
.more-icon:hover {
  background-color: #DCDEE5;
  color: #3A84FF !important;
}
.group-icon {
  font-size: 12px;
  margin-bottom: 4px;
  color: #C4C6CC;
}
.line-split {
  width: 80%;
  height: 1px;
  background: #ccc;
  margin: 10px auto;
}
.add-group-btn {
  display: flex;
  align-items: center;
}
.add-icon {
  margin-right: 10px;
}

.group-more-option {
  height: 18px;
  display: flex;
  align-items: center;
}
.close-btn {
  margin-bottom: 20px;
  text-align: center;
}

.close-manage-dialog {
    .title-icon {
        font-size: 42px;
        color: #ff9c01;
        margin-bottom: 15px;
    }
    .close-title {
        margin-top: 10px;
        white-space: normal !important;
    }
    .bk-dialog-header {
        padding: 15px 0;
    }
    .bk-dialog-title {
        height: 26px !important;
        overflow: initial !important;
    }
    .confirm-close {
        margin: 15px 0 30px;
    }
    .close-tips {
      padding: 20px;
      background: #f5f6fa;
          p {
            display: flex;
            align-items: center;
            justify-content: center;
          }
    }
    .warning-icon {
      color: #ff9c01;
      font-size: 14px;
      position: relative;
      top: -1px;
      margin-right: 4px;
    }
    .header-warning-icon {
      font-size: 42px;
    }
    .option-btns {
        text-align: center;
        margin-top: 20px;
        .close-btn {
            margin-right: 10px;
            margin-bottom: 0 !important;
        }
        .btn {
            width: 88px;
        }
    }
}
</style>
<style lang="scss">
.group-more-option .bk-tooltip-ref {
  height: 18px;
  display: flex;
  align-items: center;
}
</style>
