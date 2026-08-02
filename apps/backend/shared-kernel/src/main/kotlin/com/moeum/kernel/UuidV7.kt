package com.moeum.kernel

import com.fasterxml.uuid.Generators
import java.util.UUID

object UuidV7 {
    private val generator = Generators.timeBasedEpochGenerator()

    fun generate(): UUID = generator.generate()
}
