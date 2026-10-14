package com.lotuswallet.app.data

import java.util.UUID

/** 生成 32 位十六进制的随机主键。 */
internal fun newId(): String = UUID.randomUUID().toString().replace("-", "")
