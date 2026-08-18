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
package dev.ohs.fhir.fhirpath.server.services

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.fhirpath.FhirPathEngine
import dev.ohs.fhir.fhirpath.TraceEntry
import dev.ohs.fhir.fhirpath.server.AppVersion
import dev.ohs.fhir.fhirpath.server.ExpressionEvaluationException
import dev.ohs.fhir.fhirpath.server.InputData
import dev.ohs.fhir.fhirpath.server.InvalidFieldValueException
import dev.ohs.fhir.fhirpath.types.FhirPathDate
import dev.ohs.fhir.fhirpath.types.FhirPathDateTime
import dev.ohs.fhir.fhirpath.types.FhirPathQuantity
import dev.ohs.fhir.fhirpath.types.FhirPathTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Extension reporting where in the test resource a value came from. */
internal const val RESOURCE_PATH_EXTENSION_URL =
  "http://fhir.forms-lab.com/StructureDefinition/resource-path"

/** Code system for the `code` of a quantity whose unit is a UCUM unit. */
internal const val UCUM_SYSTEM = "http://unitsofmeasure.org"

/** FHIRPath calendar duration keywords. Any other quantity unit is a UCUM code. */
private val CALENDAR_DURATION_UNITS =
  setOf(
    "year",
    "years",
    "month",
    "months",
    "week",
    "weeks",
    "day",
    "days",
    "hour",
    "hours",
    "minute",
    "minutes",
    "second",
    "seconds",
    "millisecond",
    "milliseconds",
  )

/**
 * Base implementation of [FhirPathService] for a specific FHIR version.
 *
 * To add support for a new FHIR version, extend this class with:
 * - [Param] — the version's `Parameters.Parameter` type
 * - [Resource] — the version's base `Resource` type
 *
 * ## Minimum required overrides
 * |Member                 |Purpose                                                                              |
 * |-----------------------|-------------------------------------------------------------------------------------|
 * |[fhirVersion]          |FHIR version tag for the `evaluator` output parameter (e.g. `"R4"`)                  |
 * |[getFhirPathEngine]    |Get Version-specific [FhirPathEngine] instance (e.g. `FhirPathEngine.forR4()`)       |
 * |[decodeResource]       |Deserialise a JSON string into the version's [Resource] type                         |
 * |[buildFhirParameters]  |Serialise a `Parameters` resource (with the given [Param] list) back to a JSON string|
 * |[makeStringParameter]  |Construct a string-valued `Parameters.Parameter` with an optional list of child parts|
 * |[makeGroupParameter]   |Construct a group `Parameters.Parameter` (no value, only child parts)                |
 * |[makeResourceParameter]|Construct a resource-valued `Parameters.Parameter`                                   |
 *
 * ## FHIRPath primitive type converters
 *
 * The engine returns its own primitive types for numeric, temporal, and scalar results. Each maps
 * to a specific FHIR parameter type; implement one method per primitive:
 *
 * | Method                     | Engine type        | FHIR parameter type |
 * |----------------------------|--------------------|---------------------|
 * | [makeDecimalParameter]     | [BigDecimal]       | `decimal`           |
 * | [makeQuantityParameter]    | [FhirPathQuantity] | `quantity`          |
 * | [makeDateTimeParameter]    | [FhirPathDateTime] | `dateTime`          |
 * | [makeDateParameter]        | [FhirPathDate]     | `date`              |
 * | [makeTimeParameter]        | [FhirPathTime]     | `time`              |
 * | [makeIntegerParameter]     | [Int]              | `integer`           |
 * | [makeBooleanParameter]     | [Boolean]          | `boolean`           |
 * | [makeStringValueParameter] | [String]           | `string`            |
 *
 * ## FHIR model type converter
 *
 * [convertFhirTypeToParameter] receives any value that is not one of the FHIRPath primitives above
 * — typically a version-specific FHIR data type (e.g. `Coding`, `Period`, `Reference`). Use a
 * `when` expression to wrap each recognised type in its corresponding `Parameters.Parameter.Value`
 * subclass. The `else` branch should fall back to a JSON extension parameter using
 * `DynamicLookupSerializer` for any unrecognised type.
 */
internal abstract class FhirPathService<Param : Any, Resource : Any> {

  /**
   * FHIR version this service evaluates against, e.g. `R4` — the bracketed part of
   * [evaluatorLabel].
   */
  protected abstract val fhirVersion: String

  /**
   * The `evaluator` output parameter: engine name, engine version, and FHIR version in brackets, as
   * the FHIRPath Lab API requires (its own example is `Java 6.6.5 (R4B)`).
   */
  private val evaluatorLabel: String
    get() = "Kotlin FHIRPath ${AppVersion.engine} ($fhirVersion)"

  /** JSON codec used to round-trip the final `Parameters` resource. */
  protected val json = Json {}

  /** Get Version-specific FHIRPath evaluation engine. */
  protected abstract fun getFhirPathEngine(): FhirPathEngine

  /** Deserialize [jsonString] into this version's [Resource] type. */
  protected abstract fun decodeResource(jsonString: String): Resource

  /** Wrap a [BigDecimal] engine result as a `decimal` parameter. */
  protected abstract fun makeDecimalParameter(value: BigDecimal): Param

  /** Wrap a [FhirPathQuantity] engine result as a `quantity` parameter. */
  protected abstract fun makeQuantityParameter(value: FhirPathQuantity): Param

  /** Wrap a [FhirPathDateTime] engine result as a `dateTime` parameter. */
  protected abstract fun makeDateTimeParameter(value: FhirPathDateTime): Param

  /** Wrap a [FhirPathDate] engine result as a `date` parameter. */
  protected abstract fun makeDateParameter(value: FhirPathDate): Param

  /** Wrap a [FhirPathTime] engine result as a `time` parameter. */
  protected abstract fun makeTimeParameter(value: FhirPathTime): Param

  /** Wrap an [Int] engine result as an `integer` parameter. */
  protected abstract fun makeIntegerParameter(value: Int): Param

  /** Wrap a [Boolean] engine result as a `boolean` parameter. */
  protected abstract fun makeBooleanParameter(value: Boolean): Param

  /** Wrap a [String] engine result as a `string` parameter. */
  protected abstract fun makeStringValueParameter(value: String): Param

  /**
   * Wrap a version-specific FHIR model type as a parameter.
   *
   * Called for any eval result that is not a FHIRPath primitive. Use a `when` expression over the
   * version's concrete data types (e.g. `Coding`, `Period`). The `else` branch should produce a
   * JSON extension fallback for unknown types.
   */
  protected abstract fun convertFhirTypeToParameter(value: Any): Param

  /**
   * Convert a single FHIRPath evaluation result to a [Param].
   *
   * Dispatches FHIRPath primitive types to the dedicated `make*Parameter` methods and delegates
   * everything else to [convertFhirTypeToParameter].
   */
  protected fun convertEvalResultToParameter(evalResult: Any): Param =
    when (evalResult) {
      is BigDecimal -> makeDecimalParameter(evalResult)
      is FhirPathQuantity -> makeQuantityParameter(evalResult)
      is FhirPathDateTime -> makeDateTimeParameter(evalResult)
      is FhirPathDate -> makeDateParameter(evalResult)
      is FhirPathTime -> makeTimeParameter(evalResult)
      is Int -> makeIntegerParameter(evalResult)
      is Boolean -> makeBooleanParameter(evalResult)
      is String -> makeStringValueParameter(evalResult)
      else -> convertFhirTypeToParameter(evalResult)
    }

  /**
   * Build a string-valued parameter named [name] with value [value], optionally with child [parts].
   */
  protected abstract fun makeStringParameter(
    name: String,
    value: String?,
    parts: List<Param> = emptyList(),
  ): Param

  /** Build a group parameter named [name] with no value and the given child [parts]. */
  protected abstract fun makeGroupParameter(name: String, parts: List<Param>): Param

  /** Return [param] renamed to [name]. */
  protected abstract fun renameParameter(param: Param, name: String): Param

  /** Return [param] with a [RESOURCE_PATH_EXTENSION_URL] extension for [path] added. */
  protected abstract fun addResourcePath(param: Param, path: String): Param

  /** Build a resource-valued parameter named [name] containing [resource]. */
  protected abstract fun makeResourceParameter(name: String, resource: Resource): Param

  /**
   * Serialize a `Parameters` resource with the given [id] and top-level [params] to a JSON string.
   */
  protected abstract fun buildFhirParameters(id: String, params: List<Param>): String

  /**
   * Evaluates [expression], reporting a parse or evaluation failure as an
   * [ExpressionEvaluationException].
   *
   * The engine raises plain exceptions for faults in the submitted expression — an unparseable
   * expression, an unknown variable, an unsupported operand pairing. Those are the caller's, not
   * the server's, and reporting them as internal errors puts text like `Internal server error:
   * token index 7 out of range 0..6` in front of whoever typed the expression.
   */
  private fun FhirPathEngine.evaluateOrFail(
    expression: String,
    base: Any,
    variables: Map<String, Any?>,
  ): Collection<Any> =
    try {
      evaluateExpression(expression, base, variables)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw ExpressionEvaluationException(
        e.message ?: e::class.simpleName ?: "evaluation failed",
        e,
      )
    }

  /**
   * Display form of a FHIRPath quantity unit.
   *
   * UCUM units keep the quotes of the expression literal they came from (`1 'mg'` yields `'mg'`),
   * which is not a unit. Calendar durations (`1 year`) arrive unquoted and are left alone.
   */
  protected fun quantityUnit(unit: String?): String? = unit?.removeSurrounding("'")

  /**
   * UCUM code of [unit], or `null` when it names a calendar duration, which has no UCUM code and so
   * belongs in `Quantity.unit` alone.
   */
  protected fun ucumCode(unit: String?): String? =
    quantityUnit(unit)?.takeIf { it !in CALENDAR_DURATION_UNITS }

  /**
   * The `result.valueString` describing which context item a result belongs to, before the index is
   * appended — `Patient.name` for a [contextExpression] of `name` or of `Patient.name`.
   *
   * A context expression that already names the resource type is left alone; prefixing it again
   * would put `Patient.Patient.name[0]` in front of whoever wrote it.
   */
  private fun contextLabel(resourceType: String, contextExpression: String): String =
    if (contextExpression == resourceType || contextExpression.startsWith("$resourceType."))
      contextExpression
    else "$resourceType.$contextExpression"

  /** FHIR type name of [value] — `Patient`, `HumanName`, or `Patient#Contact` for a backbone. */
  protected fun fhirTypeName(value: Any): String {
    val type = value::class.java
    val enclosing = type.enclosingClass
    return if (enclosing != null) "${enclosing.simpleName}#${type.simpleName}" else type.simpleName
  }

  /**
   * Re-roots an engine trace path onto [contextLabel].
   *
   * The engine reports paths relative to the base it evaluated against, so under a context
   * expression they open at the context item's own type — `HumanName.given[0]`. The lab resolves
   * paths against the test resource, so that leading segment is swapped for the context label to
   * give `Patient.name[0].given[0]`. Without a context expression the base is already the resource
   * and the path needs no change.
   */
  private fun rootedPath(path: String, contextLabel: String?): String {
    if (contextLabel == null) return path
    val withinContext = path.substringAfter('.', missingDelimiterValue = "")
    return if (withinContext.isEmpty()) contextLabel else "$contextLabel.$withinContext"
  }

  private fun buildTracingParameters(
    traces: Map<String, List<TraceEntry>>,
    contextLabel: String? = null,
  ) =
    traces.map { entry ->
      makeStringParameter(
        name = "trace",
        value = entry.key,
        // Each traced value carries the path it came from, which the lab links back into the
        // displayed resource.
        parts =
          entry.value.map {
            addResourcePath(
              convertEvalResultToParameter(it.value),
              rootedPath(it.path, contextLabel),
            )
          },
      )
    }

  suspend fun evaluate(inputData: InputData): JsonElement =
    withContext(Dispatchers.Default) {
      val fhirpathEngine = getFhirPathEngine()
      val resource =
        try {
          decodeResource(inputData.resourceStr)
        } catch (e: Exception) {
          throw InvalidFieldValueException("Invalid 'resource': ${e.message}")
        }
      val resourceType = resource::class.simpleName!!

      // Per the FHIRPath Lab API's evaluation notes, `%resource` and `%rootResource` are the test
      // resource, and `%context` is the item under evaluation — the test resource when no context
      // expression narrows it. These take precedence over caller-supplied bindings of the same
      // name, since the API defines them.
      val standardVariables = mapOf("resource" to resource, "rootResource" to resource)
      val contextVariables = inputData.variables + standardVariables

      val results =
        if (!inputData.contextExpression.isNullOrBlank()) {
          fhirpathEngine
            .evaluateOrFail(
              inputData.contextExpression,
              base = resource,
              variables = contextVariables + mapOf("context" to resource),
            )
            .mapIndexed { index, contextValue ->
              val label = "${contextLabel(resourceType, inputData.contextExpression)}[$index]"
              val expressionResult =
                fhirpathEngine.evaluateOrFail(
                  inputData.expression,
                  base = contextValue,
                  variables = contextVariables + mapOf("context" to contextValue),
                )
              makeStringParameter(
                name = "result",
                value = label,
                parts =
                  expressionResult.map { convertEvalResultToParameter(it) } +
                    buildTracingParameters(fhirpathEngine.traces, contextLabel = label),
              )
            }
        } else {
          val expressionResult =
            fhirpathEngine.evaluateOrFail(
              inputData.expression,
              base = resource,
              variables = contextVariables + mapOf("context" to resource),
            )
          listOf(
            makeGroupParameter(
              name = "result",
              parts =
                expressionResult.map { convertEvalResultToParameter(it) } +
                  buildTracingParameters(fhirpathEngine.traces),
            )
          )
        }

      val metaParameters: List<Param> = buildList {
        add(makeStringParameter(name = "evaluator", value = evaluatorLabel))
        inputData.contextExpression?.let { add(makeStringParameter(name = "context", value = it)) }
        add(makeStringParameter(name = "expression", value = inputData.expression))
        add(makeResourceParameter(name = "resource", resource = resource))
        inputData.terminologyServer?.let {
          add(makeStringParameter(name = "terminologyServerUrl", value = it))
        }
        if (inputData.variables.isNotEmpty()) {
          add(
            makeGroupParameter(
              name = "variables",
              parts =
                inputData.variables.map { (name, value) ->
                  // Echoed in its own `value[x]`, as the API's example response does. A variable
                  // declared with no `value[x]` echoes as a bare name.
                  if (value == null) makeGroupParameter(name = name, parts = emptyList())
                  else renameParameter(convertEvalResultToParameter(value), name)
                },
            )
          )
        }
      }

      val parametersJsonString =
        buildFhirParameters(
          id = "fhirpath",
          params = listOf(makeGroupParameter(name = "parameters", parts = metaParameters)) + results,
        )

      json.parseToJsonElement(parametersJsonString)
    }
}
