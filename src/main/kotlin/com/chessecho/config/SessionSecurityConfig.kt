package com.chessecho.config

import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.web.AuthenticatedPrincipalArgumentResolver
import com.chessecho.web.CsrfEnforcementInterceptor
import com.chessecho.web.SessionAuthenticationFilter
import com.chessecho.web.SessionCookieWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the request-security beans (session filter, CSRF interceptor,
 * principal argument resolver, cookie writer, and the MVC wiring) as explicit
 * beans. They are intentionally not stereotype-annotated so that unrelated
 * `@WebMvcTest` slices do not auto-detect the session filter/interceptor/resolver
 * (which transitively need [IdentitySessionService]); this plain `@Configuration`
 * is only loaded by the full application context.
 */
@Configuration
class SessionSecurityConfig {
    @Bean
    fun sessionCookieWriter(properties: SessionCookieProperties): SessionCookieWriter = SessionCookieWriter(properties)

    @Bean
    fun authenticatedPrincipalArgumentResolver(): AuthenticatedPrincipalArgumentResolver = AuthenticatedPrincipalArgumentResolver()

    @Bean
    fun csrfEnforcementInterceptor(properties: SessionCookieProperties): CsrfEnforcementInterceptor = CsrfEnforcementInterceptor(properties)

    @Bean
    fun sessionAuthenticationFilter(
        identitySessionService: IdentitySessionService,
        properties: SessionCookieProperties,
    ): SessionAuthenticationFilter = SessionAuthenticationFilter(identitySessionService, properties)

    @Bean
    fun sessionWebConfig(
        authenticatedPrincipalArgumentResolver: AuthenticatedPrincipalArgumentResolver,
        csrfEnforcementInterceptor: CsrfEnforcementInterceptor,
    ): SessionWebConfig = SessionWebConfig(authenticatedPrincipalArgumentResolver, csrfEnforcementInterceptor)
}
