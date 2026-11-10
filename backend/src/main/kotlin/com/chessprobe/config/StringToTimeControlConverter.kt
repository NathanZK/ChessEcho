package com.chessprobe.config

import com.chessprobe.domain.TimeControl
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class StringToTimeControlConverter : Converter<String, TimeControl> {
    override fun convert(source: String): TimeControl =
        TimeControl.fromExternal(source)
            ?: throw IllegalArgumentException("Invalid timeControl '$source'. Supported timeControls: RAPID, BLITZ, BULLET, CLASSICAL.")
}
