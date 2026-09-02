package com.chessecho.web

import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Injects the [AuthenticatedPrincipal] placed on the request by
 * [SessionAuthenticationFilter]. It fails closed: when a handler declares an
 * [AuthenticatedPrincipal] parameter and no principal is present, it throws
 * [UnauthenticatedException] (mapped to 401). It never exposes the raw secret.
 */
class AuthenticatedPrincipalArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == AuthenticatedPrincipal::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val principal = webRequest.getAttribute(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
        return principal ?: throw UnauthenticatedException()
    }
}
