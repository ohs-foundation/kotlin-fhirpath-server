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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.fhirpath.server.services.FhirPathR4BService
import dev.ohs.fhir.fhirpath.server.services.FhirPathR4Service
import dev.ohs.fhir.fhirpath.server.services.FhirPathR5Service
import dev.ohs.fhir.fhirpath.types.FhirPathDate
import dev.ohs.fhir.fhirpath.types.FhirPathDateTime
import dev.ohs.fhir.fhirpath.types.FhirPathQuantity
import dev.ohs.fhir.fhirpath.types.FhirPathTime
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * FHIRPath Lab custom engine config, resolved against the process working directory.
 *
 * Deliberately kept out of the jar and read on each request, so it can be edited without restarting
 * the server. The Dockerfile copies it next to `app.jar` in the runtime image's `WORKDIR`.
 */
private const val FHIRPATH_LAB_CONFIG_PATH = "kotlin-fhirpath-config.json"

/** Extension carrying a resource that is serialized into a string rather than sent inline. */
private const val JSON_VALUE_EXTENSION_URL =
  "http://fhir.forms-lab.com/StructureDefinition/json-value"

/** As [JSON_VALUE_EXTENSION_URL], but XML — which this server does not accept. */
private const val XML_VALUE_EXTENSION_URL =
  "http://fhir.forms-lab.com/StructureDefinition/xml-value"

fun Application.configureRouting() {
  install(AutoHeadResponse)
  install(DoubleReceive)

  routing {
    get("/") {
      call.respond(
        buildJsonObject {
          put("message", "Kotlin FHIRPath server is running!")
          put("version", AppVersion.current)
          put(
            "endpoints",
            buildJsonObject {
              put("/health", "GET - Health check")
              put("/kotlin-fhirpath-config.json", "GET - FHIRPath Lab custom engine configuration")
              put("/fhirpath-r4", "POST - Evaluate R4 FHIRPath expressions")
              put("/fhirpath-r4b", "POST - Evaluate R4B FHIRPath expressions")
              put("/fhirpath-r5", "POST - Evaluate R5 FHIRPath expressions")
            },
          )
        }
      )
    }

    get("/health") {
      call.respond(mapOf("status" to "healthy", "timestamp" to Clock.System.now().toString()))
    }
    // FHIRPath Lab custom engine config for local testing — use via the `?config=` query parameter.
    // See: https://github.com/brianpos/fhirpath-lab/blob/develop/docs/custom-configuration.md
    get("/$FHIRPATH_LAB_CONFIG_PATH") {
      // `LocalFileContent` throws IOException on a missing file, which would escape the route as a
      // 500. A config that isn't there is a 404.
      val config = File(FHIRPATH_LAB_CONFIG_PATH)
      if (config.isFile) call.respondFile(config) else call.respond(HttpStatusCode.NotFound)
    }

    post("/fhirpath-r4") { parseAndEvaluateFhirPath(call) { FhirPathR4Service().evaluate(it) } }
    post("/fhirpath-r4b") { parseAndEvaluateFhirPath(call) { FhirPathR4BService().evaluate(it) } }
    post("/fhirpath-r5") { parseAndEvaluateFhirPath(call) { FhirPathR5Service().evaluate(it) } }
  }
}

private suspend fun parseAndEvaluateFhirPath(
  routingCall: RoutingCall,
  evaluate: suspend (InputData) -> JsonElement,
) {
  val inputData =
    try {
      parseContentStringData(routingCall.receive<JsonObject>())
    } catch (e: MissingRequiredFieldException) {
      routingCall.respond(
        HttpStatusCode.BadRequest,
        createOperationOutcome("error", "required", e.message ?: ""),
      )
      return
    } catch (e: InvalidFieldValueException) {
      routingCall.respond(
        HttpStatusCode.BadRequest,
        createOperationOutcome("error", "invalid", e.message ?: ""),
      )
      return
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // A body that is not a JSON object fails in `receive`, before the parser sees it. Uncaught,
      // that reaches the caller as Ktor's default error page rather than an OperationOutcome.
      routingCall.respond(
        HttpStatusCode.BadRequest,
        createOperationOutcome("error", "structure", "Malformed request body: ${e.message}"),
      )
      return
    }

  try {
    routingCall.respond(HttpStatusCode.OK, evaluate(inputData))
  } catch (e: CancellationException) {
    // The call is already gone — there is nobody left to answer, and reporting a 500 here would
    // mask the cancellation instead of letting it unwind.
    throw e
  } catch (e: ExpressionEvaluationException) {
    routingCall.respond(
      HttpStatusCode.BadRequest,
      createOperationOutcome("error", "processing", e.message ?: ""),
    )
  } catch (e: InvalidFieldValueException) {
    routingCall.respond(
      HttpStatusCode.BadRequest,
      createOperationOutcome("error", "invalid", e.message ?: ""),
    )
  } catch (e: Exception) {
    routingCall.respond(
      HttpStatusCode.InternalServerError,
      createOperationOutcome("error", "exception", "Internal server error: ${e.message}"),
    )
  }
}

/**
 * Parses a FHIR [Parameters](https://www.hl7.org/fhir/parameters.html) resource into [InputData].
 *
 * Required parameters: `expression`, `resource`. Optional parameters: `context`, `variables`,
 * `terminologyserver`.
 *
 * Example input:
 * ```json
 * {
 *   "resourceType": "Parameters",
 *   "parameter": [
 *     { "name": "expression", "valueString": "Patient.name.given" },
 *     { "name": "resource", "resource": { "resourceType": "Patient", "name": [{ "given": ["John"] }] } },
 *     { "name": "context", "valueString": "Patient" },
 *     { "name": "terminologyserver", "valueString": "https://tx.fhir.org/r4" },
 *     {
 *       "name": "variables",
 *       "part": [
 *         { "name": "myVar", "valueString": "someValue" }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * @throws MissingRequiredFieldException if `parameter`, `expression`, or `resource` is absent.
 * @throws InvalidFieldValueException if `resourceType` is not `"Parameters"`, a parameter name is
 *   repeated, or a field holds a value of the wrong shape.
 */
private suspend fun parseContentStringData(contentJSObject: JsonObject): InputData =
  withContext(Dispatchers.Default) {
    if (contentJSObject.stringFieldOrNull("resourceType") != "Parameters") {
      throw InvalidFieldValueException("Expected FHIR Parameters resource")
    }
    val parameterField =
      contentJSObject["parameter"]
        ?: throw MissingRequiredFieldException("Missing required field: 'parameter'")
    val inputParameters =
      (parameterField as? JsonArray)?.map {
        it as? JsonObject
          ?: throw InvalidFieldValueException("Expected an object in 'parameter', found $it")
      } ?: throw InvalidFieldValueException("Expected an array for 'parameter'")

    val contextStr = inputParameters.parameterNamed("context")?.stringFieldOrNull("valueString")
    val expressionStr =
      inputParameters.parameterNamed("expression")?.stringFieldOrNull("valueString")
        ?: throw MissingRequiredFieldException("Missing required parameter: 'expression'")
    // `variables` is 0..* in the FHIRPath Lab API, so bindings from every occurrence are merged.
    val variables =
      inputParameters
        .parametersNamed("variables")
        .flatMap { variablesParameter ->
          val parts = variablesParameter["part"] ?: return@flatMap emptyList()
          (parts as? JsonArray)?.map {
            it as? JsonObject
              ?: throw InvalidFieldValueException(
                "Expected an object in 'variables.part', found $it"
              )
          } ?: throw InvalidFieldValueException("Expected an array for 'variables.part'")
        }
        .fold(mutableMapOf<String, Any?>()) { bindings, part ->
          val variableName =
            part.stringFieldOrNull("name")
              ?: throw MissingRequiredFieldException("Missing required parameter: 'part.name'")
          // Rejected rather than silently overwritten: only one of the two bindings could win, and
          // the caller has no way to tell which.
          if (variableName in bindings) {
            throw InvalidFieldValueException("Duplicate variable: '$variableName'")
          }
          bindings.also { it[variableName] = part.variableValue(variableName) }
        }
    val resourceString =
      inputParameters.parameterNamed("resource")?.resourceJson()
        ?: throw MissingRequiredFieldException("Missing required parameter: 'resource'")

    // Parsed but unused: the engine has no terminology functions to point at a server yet.
    val terminologyServer =
      inputParameters.parameterNamed("terminologyserver")?.stringFieldOrNull("valueString")
    InputData(contextStr, expressionStr, resourceString, variables, terminologyServer)
  }

/**
 * The resource JSON of a `resource` parameter.
 *
 * The FHIRPath Lab API allows the resource to arrive either inline as `resource`, or as a string
 * extension holding its serialized form — the route used for XML input and for engines that only
 * accept R4 input parameters.
 *
 * @throws InvalidFieldValueException if the resource is malformed or XML.
 */
private fun JsonObject.resourceJson(): String? {
  this["resource"]?.let {
    return (it as? JsonObject)?.toString()
      ?: throw InvalidFieldValueException("Expected an object for 'resource'")
  }
  val extensions =
    (this["extension"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
  extensions
    .firstOrNull { it.stringFieldOrNull("url") == JSON_VALUE_EXTENSION_URL }
    ?.let {
      return it.stringFieldOrNull("valueString")
        ?: throw InvalidFieldValueException("Expected a 'valueString' on the json-value extension")
    }
  if (extensions.any { it.stringFieldOrNull("url") == XML_VALUE_EXTENSION_URL }) {
    throw InvalidFieldValueException(
      "The 'resource' parameter uses the xml-value extension; this server accepts JSON resources only"
    )
  }
  return null
}

/**
 * Reads [field] as a JSON string, or `null` when the field is absent or JSON `null`.
 *
 * @throws InvalidFieldValueException if the field holds anything other than a JSON string. Reading
 *   it with `jsonPrimitive` instead would raise an `IllegalArgumentException` that no caller
 *   handles, turning a bad request into an unhandled 500.
 */
private fun JsonObject.stringFieldOrNull(field: String): String? =
  when (val value = this[field]) {
    null,
    JsonNull -> null
    is JsonPrimitive ->
      if (value.isString) value.content
      else
        throw InvalidFieldValueException("Expected a string for '$field', found ${value.content}")
    else -> throw InvalidFieldValueException("Expected a string for '$field', found $value")
  }

/** Returns every `parameter` entry named [name], in request order. */
private fun List<JsonObject>.parametersNamed(name: String): List<JsonObject> = filter {
  it.stringFieldOrNull("name") == name
}

/**
 * Returns the `parameter` entry named [name], or `null` when there is none.
 *
 * FHIR permits repeated `parameter` names, and the FHIRPath Lab API uses that for `variables`. The
 * parameters this reads — `expression`, `resource`, `context`, `terminologyserver` — are all 0..1
 * or 1..1, so a repeat is reported as such rather than as a missing parameter.
 *
 * @throws InvalidFieldValueException if [name] appears more than once.
 */
private fun List<JsonObject>.parameterNamed(name: String): JsonObject? {
  val matches = filter { it.stringFieldOrNull("name") == name }
  if (matches.size > 1) {
    throw InvalidFieldValueException("Duplicate parameter: '$name'")
  }
  return matches.firstOrNull()
}

/**
 * FHIR primitive `value[x]` types that carry a JSON string the engine reads as a FHIRPath string.
 */
private val STRING_VALUE_FIELDS =
  setOf(
    "valueString",
    "valueCode",
    "valueId",
    "valueUri",
    "valueUrl",
    "valueCanonical",
    "valueOid",
    "valueUuid",
    "valueMarkdown",
    "valueBase64Binary",
  )

/**
 * Reads the `value[x]` of a `variables.part` entry as the FHIRPath value to bind to [variableName].
 *
 * `variables.part.value` is 0..1, so a part with no value binds `null` — the variable is declared
 * but empty. What is *not* acceptable is binding `null` for a value that is present but of a type
 * this does not read: FHIRPath propagates empty, so a dropped variable makes every reference to it
 * evaluate to empty and hands the caller a confidently wrong answer under a 200. Those are rejected
 * instead.
 *
 * Covers the FHIR primitive types, which map onto the primitives the engine speaks. Complex types
 * (`valueQuantity`, `valueHumanName`, …) are not yet supported and are rejected.
 *
 * @throws InvalidFieldValueException if the `value[x]` type or content is not usable.
 */
private fun JsonObject.variableValue(variableName: String): Any? {
  val valueField = keys.firstOrNull { it.startsWith("value") } ?: return null

  // The one complex type handled here: a quantity maps onto the engine's own `FhirPathQuantity`,
  // so it needs no version-specific FHIR model type the way a `valueHumanName` would.
  if (valueField == "valueQuantity") {
    val quantity =
      this[valueField] as? JsonObject
        ?: throw InvalidFieldValueException(
          "Expected an object 'valueQuantity' for variable '$variableName'"
        )
    return quantity.toFhirPathQuantity(variableName)
  }

  // Resolved before the value itself, so an unsupported complex type is reported as such rather
  // than as a malformed primitive.
  val convert: (JsonPrimitive) -> Any? =
    when (valueField) {
      in STRING_VALUE_FIELDS -> { it -> it.content.takeIf { _ -> it.isString } }
      "valueBoolean" -> JsonPrimitive::booleanOrNull
      "valueInteger",
      "valuePositiveInt",
      "valueUnsignedInt" -> JsonPrimitive::intOrNull
      "valueDecimal" -> { it -> runCatching { BigDecimal.parseString(it.content) }.getOrNull() }
      "valueDate" -> { it -> runCatching { FhirPathDate.fromString(it.content) }.getOrNull() }
      "valueDateTime",
      "valueInstant" -> { it ->
          runCatching { FhirPathDateTime.fromString(it.content) }.getOrNull()
        }
      "valueTime" -> { it -> runCatching { FhirPathTime.fromString(it.content) }.getOrNull() }
      else ->
        throw InvalidFieldValueException(
          "Unsupported value type '$valueField' for variable '$variableName'"
        )
    }

  val value =
    this[valueField] as? JsonPrimitive
      ?: throw InvalidFieldValueException(
        "Expected a primitive '$valueField' for variable '$variableName'"
      )
  return convert(value)
    ?: throw InvalidFieldValueException(
      "Invalid '$valueField' for variable '$variableName': ${value.content}"
    )
}

/**
 * Reads a FHIR `Quantity` as the engine's [FhirPathQuantity].
 *
 * `code` is preferred over `unit`: FHIRPath compares quantities by their UCUM code, while `unit` is
 * a display string that need not be a valid unit at all.
 *
 * @throws InvalidFieldValueException if `value` or both unit fields are absent or unusable.
 */
private fun JsonObject.toFhirPathQuantity(variableName: String): FhirPathQuantity {
  val value =
    (this["value"] as? JsonPrimitive)?.content?.let {
      runCatching { BigDecimal.parseString(it) }.getOrNull()
    }
      ?: throw InvalidFieldValueException(
        "Invalid 'valueQuantity.value' for variable '$variableName'"
      )
  val unit =
    stringFieldOrNull("code")
      ?: stringFieldOrNull("unit")
      ?: throw InvalidFieldValueException(
        "Missing 'valueQuantity.code' or 'valueQuantity.unit' for variable '$variableName'"
      )
  return FhirPathQuantity(value, unit)
}

private fun createOperationOutcome(severity: String, code: String, message: String): JsonObject {
  return buildJsonObject {
    put("resourceType", "OperationOutcome")
    put(
      "issue",
      buildJsonArray {
        add(
          buildJsonObject {
            put("severity", severity)
            put("code", code)
            put("details", buildJsonObject { put("text", message) })
          }
        )
      },
    )
  }
}
