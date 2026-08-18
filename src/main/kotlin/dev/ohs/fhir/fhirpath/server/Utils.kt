/*
 * Copyright 2025-2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.fhirpath.server

import dev.ohs.fhir.fhirpath.types.FhirPathTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer

class MissingRequiredFieldException(message: String) : SerializationException(message = message)

/**
 * Thrown when a request field is present but holds something the server cannot use — a
 * `valueString` containing an object, a repeated `parameter` name, an unsupported variable
 * `value[x]`.
 *
 * Distinct from [IllegalStateException] on purpose: `CancellationException` is an
 * `IllegalStateException` on the JVM, so catching that would swallow cancellation.
 */
class InvalidFieldValueException(message: String) : SerializationException(message = message)

/**
 * Thrown when the submitted FHIRPath expression fails to parse or evaluate.
 *
 * A fault in the caller's expression, not in the server — reported as a 400 so the engine's message
 * reaches the author of the expression rather than being labelled an internal error.
 */
class ExpressionEvaluationException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

fun FhirPathTime.toLocalTime(): LocalTime =
  LocalTime(
    hour = this.hour,
    minute = this.minute ?: 0,
    second = this.second?.intValue(exactRequired = false) ?: 0,
    nanosecond =
      this.second?.let { s ->
        ((s - s.intValue(exactRequired = false)) * 1_000_000_000).intValue(exactRequired = false)
      } ?: 0,
  )

@ExperimentalSerializationApi
class DynamicLookupSerializer : KSerializer<Any> {
  override val descriptor: SerialDescriptor =
    ContextualSerializer(Any::class, null, emptyArray()).descriptor

  @OptIn(InternalSerializationApi::class)
  override fun serialize(encoder: Encoder, value: Any) {
    val actualSerializer =
      encoder.serializersModule.getContextual(value::class) ?: value::class.serializer()
    @Suppress("UNCHECKED_CAST")
    encoder.encodeSerializableValue(actualSerializer as KSerializer<Any>, value)
  }

  override fun deserialize(decoder: Decoder): Any {
    error("Unsupported")
  }
}
