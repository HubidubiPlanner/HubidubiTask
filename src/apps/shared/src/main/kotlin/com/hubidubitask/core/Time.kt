package com.hubidubitask.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val RFC3339: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

fun nowUtc(): String = RFC3339.format(Instant.now())
