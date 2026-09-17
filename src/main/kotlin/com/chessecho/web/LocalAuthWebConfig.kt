package com.chessecho.web

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Keeps first-party authentication under the same double-submit CSRF
 * interceptor as the existing state-changing session endpoints.
 */
@Configuration
class LocalAuthWebConfig(
    private val csrfEnforcementInterceptor: CsrfEnforcementInterceptor?,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        csrfEnforcementInterceptor
            ?.let { registry.addInterceptor(it).addPathPatterns("/api/register", "/api/login") }
    }
}
