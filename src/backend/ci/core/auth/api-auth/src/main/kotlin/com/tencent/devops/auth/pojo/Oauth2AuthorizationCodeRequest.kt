package com.tencent.devops.auth.pojo

import com.tencent.devops.auth.pojo.enum.Oauth2GrantType
import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "授权码模式获取token请求报文体")
data class Oauth2AuthorizationCodeRequest(
    @get:Schema(title = "授权类型", required = true)
    override val grantType: Oauth2GrantType,
    @get:Schema(title = "授权码,用于授权码模式", required = false)
    val code: String
) : Oauth2AccessTokenRequest {
    companion object {
        const val TYPE = "AUTHORIZATION_CODE"
    }
}
