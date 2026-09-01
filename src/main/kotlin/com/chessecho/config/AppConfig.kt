package com.chessecho.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.web.client.RestClient
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@EnableAsync
@EnableConfigurationProperties(
    ChessPubApiProperties::class,
    PracticalEvidenceProperties::class,
)
class AppConfig {
    @Bean
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()

    @Bean
    fun restClient(builder: RestClient.Builder): RestClient = builder.build()

    @Bean
    fun corsConfigurer(
        stringToPlayerColorConverter: StringToPlayerColorConverter,
        stringToPlatformConverter: StringToPlatformConverter,
        stringToTimeControlConverter: StringToTimeControlConverter,
    ): WebMvcConfigurer {
        return object : WebMvcConfigurer {
            override fun addCorsMappings(registry: CorsRegistry) {
                registry.addMapping("/**")
                    .allowedOrigins("http://localhost:3000", "http://127.0.0.1:3000")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
            }

            override fun addFormatters(registry: org.springframework.format.FormatterRegistry) {
                registry.addConverter(stringToPlayerColorConverter)
                registry.addConverter(stringToPlatformConverter)
                registry.addConverter(stringToTimeControlConverter)
            }
        }
    }
}
