package com.chessecho.config

import com.chessecho.web.AuthenticatedPrincipalArgumentResolver
import com.chessecho.web.CsrfEnforcementInterceptor
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Wires the request-security extension points: the authenticated-principal
 * argument resolver and the CSRF enforcement interceptor for the state-changing
 * session endpoints. It is registered as a bean by [SessionSecurityConfig] (not a
 * component-scanned stereotype) so it is not auto-detected into unrelated
 * `@WebMvcTest` slices.
 */
class SessionWebConfig(
    private val authenticatedPrincipalArgumentResolver: AuthenticatedPrincipalArgumentResolver,
    private val csrfEnforcementInterceptor: CsrfEnforcementInterceptor,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(authenticatedPrincipalArgumentResolver)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(csrfEnforcementInterceptor)
            .addPathPatterns("/api/logout", "/api/dev/session")
    }
}
