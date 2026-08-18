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
import dev.ohs.fhir.fhirpath.forR4B
import dev.ohs.fhir.fhirpath.server.DynamicLookupSerializer
import dev.ohs.fhir.fhirpath.server.toLocalTime
import dev.ohs.fhir.fhirpath.types.FhirPathDate
import dev.ohs.fhir.fhirpath.types.FhirPathDateTime
import dev.ohs.fhir.fhirpath.types.FhirPathQuantity
import dev.ohs.fhir.fhirpath.types.FhirPathTime
import dev.ohs.fhir.model.r4b.Address
import dev.ohs.fhir.model.r4b.Age
import dev.ohs.fhir.model.r4b.Annotation as FhirAnnotation
import dev.ohs.fhir.model.r4b.Attachment
import dev.ohs.fhir.model.r4b.Base64Binary
import dev.ohs.fhir.model.r4b.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4b.Canonical
import dev.ohs.fhir.model.r4b.Code
import dev.ohs.fhir.model.r4b.CodeableConcept
import dev.ohs.fhir.model.r4b.Coding
import dev.ohs.fhir.model.r4b.ContactDetail
import dev.ohs.fhir.model.r4b.ContactPoint
import dev.ohs.fhir.model.r4b.Contributor
import dev.ohs.fhir.model.r4b.Count
import dev.ohs.fhir.model.r4b.DataRequirement
import dev.ohs.fhir.model.r4b.Date
import dev.ohs.fhir.model.r4b.DateTime
import dev.ohs.fhir.model.r4b.Decimal
import dev.ohs.fhir.model.r4b.Distance
import dev.ohs.fhir.model.r4b.Dosage
import dev.ohs.fhir.model.r4b.Duration
import dev.ohs.fhir.model.r4b.Expression
import dev.ohs.fhir.model.r4b.Extension
import dev.ohs.fhir.model.r4b.FhirDate
import dev.ohs.fhir.model.r4b.FhirDateTime
import dev.ohs.fhir.model.r4b.HumanName
import dev.ohs.fhir.model.r4b.Id
import dev.ohs.fhir.model.r4b.Identifier
import dev.ohs.fhir.model.r4b.Instant
import dev.ohs.fhir.model.r4b.Integer as FhirInteger
import dev.ohs.fhir.model.r4b.Markdown
import dev.ohs.fhir.model.r4b.Meta
import dev.ohs.fhir.model.r4b.Money
import dev.ohs.fhir.model.r4b.Oid
import dev.ohs.fhir.model.r4b.ParameterDefinition
import dev.ohs.fhir.model.r4b.Parameters
import dev.ohs.fhir.model.r4b.Period
import dev.ohs.fhir.model.r4b.PositiveInt
import dev.ohs.fhir.model.r4b.Quantity
import dev.ohs.fhir.model.r4b.Range
import dev.ohs.fhir.model.r4b.Ratio
import dev.ohs.fhir.model.r4b.Reference
import dev.ohs.fhir.model.r4b.RelatedArtifact
import dev.ohs.fhir.model.r4b.Resource
import dev.ohs.fhir.model.r4b.SampledData
import dev.ohs.fhir.model.r4b.Signature
import dev.ohs.fhir.model.r4b.String as FhirString
import dev.ohs.fhir.model.r4b.Time
import dev.ohs.fhir.model.r4b.Timing
import dev.ohs.fhir.model.r4b.TriggerDefinition
import dev.ohs.fhir.model.r4b.UnsignedInt
import dev.ohs.fhir.model.r4b.Uri
import dev.ohs.fhir.model.r4b.Url
import dev.ohs.fhir.model.r4b.UsageContext
import dev.ohs.fhir.model.r4b.Uuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

internal class FhirPathR4BService : FhirPathService<Parameters.Parameter, Resource>() {
  override val fhirVersion = "R4B"

  private val resourceParser = Json

  override fun getFhirPathEngine() = FhirPathEngine.forR4B()

  override fun decodeResource(jsonString: String) =
    resourceParser.decodeFromString<Resource>(jsonString)

  override fun buildFhirParameters(id: String, params: List<Parameters.Parameter>) =
    resourceParser.encodeToString(Parameters(id = id, parameter = params))

  override fun makeStringParameter(
    name: String,
    value: String?,
    parts: List<Parameters.Parameter>,
  ): Parameters.Parameter =
    if (parts.isEmpty())
      Parameters.Parameter(
        name = FhirString(value = name),
        value = Parameters.Parameter.Value.String(value = FhirString(value = value)),
      )
    else
      Parameters.Parameter(
        name = FhirString(value = name),
        value = Parameters.Parameter.Value.String(value = FhirString(value = value)),
        part = parts,
      )

  override fun makeGroupParameter(name: String, parts: List<Parameters.Parameter>) =
    Parameters.Parameter(name = FhirString(value = name), part = parts)

  override fun renameParameter(param: Parameters.Parameter, name: String) =
    param.copy(name = FhirString(value = name))

  override fun addResourcePath(param: Parameters.Parameter, path: String) =
    param.copy(
      extension =
        param.extension +
          Extension(
            url = RESOURCE_PATH_EXTENSION_URL,
            value = Extension.Value.String(value = FhirString(value = path)),
          )
    )

  override fun makeResourceParameter(name: String, resource: Resource) =
    Parameters.Parameter(name = FhirString(value = name), resource = resource)

  override fun makeDecimalParameter(value: BigDecimal) =
    Parameters.Parameter(
      name = FhirString(value = "decimal"),
      value = Parameters.Parameter.Value.Decimal(value = Decimal(value = value)),
    )

  override fun makeQuantityParameter(value: FhirPathQuantity) =
    Parameters.Parameter(
      name = FhirString(value = "Quantity"),
      value =
        Parameters.Parameter.Value.Quantity(
          value =
            Quantity(
              value = Decimal(value = value.value),
              unit = FhirString(value = quantityUnit(value.unit)),
              system = ucumCode(value.unit)?.let { Uri(value = UCUM_SYSTEM) },
              code = ucumCode(value.unit)?.let { Code(value = it) },
            )
        ),
    )

  override fun makeDateTimeParameter(value: FhirPathDateTime) =
    Parameters.Parameter(
      name = FhirString(value = "dateTime"),
      value =
        Parameters.Parameter.Value.DateTime(
          value = DateTime(value = FhirDateTime.fromString(value.toString()))
        ),
    )

  override fun makeDateParameter(value: FhirPathDate) =
    Parameters.Parameter(
      name = FhirString(value = "date"),
      value =
        Parameters.Parameter.Value.Date(value = Date(value = FhirDate.fromString(value.toString()))),
    )

  override fun makeTimeParameter(value: FhirPathTime) =
    Parameters.Parameter(
      name = FhirString(value = "time"),
      value = Parameters.Parameter.Value.Time(value = Time(value = value.toLocalTime())),
    )

  override fun makeIntegerParameter(value: Int) =
    Parameters.Parameter(
      name = FhirString(value = "integer"),
      value = Parameters.Parameter.Value.Integer(value = FhirInteger(value = value)),
    )

  override fun makeBooleanParameter(value: Boolean) =
    Parameters.Parameter(
      name = FhirString(value = "boolean"),
      value = Parameters.Parameter.Value.Boolean(value = FhirBoolean(value = value)),
    )

  override fun makeStringValueParameter(value: String) =
    Parameters.Parameter(
      // An empty string is flagged by name: serializers routinely drop empty values, so the name is
      // the only reliable signal that the result was an empty string rather than nothing at all.
      name = FhirString(value = if (value.isEmpty()) "empty-string" else "string"),
      value = Parameters.Parameter.Value.String(value = FhirString(value = value)),
    )

  @OptIn(ExperimentalSerializationApi::class)
  override fun convertFhirTypeToParameter(value: Any): Parameters.Parameter =
    when (value) {
      is Base64Binary ->
        Parameters.Parameter(
          name = FhirString(value = "base64Binary"),
          value = Parameters.Parameter.Value.Base64Binary(value),
        )
      is Canonical ->
        Parameters.Parameter(
          name = FhirString(value = "canonical"),
          value = Parameters.Parameter.Value.Canonical(value),
        )
      is Code ->
        Parameters.Parameter(
          name = FhirString(value = "code"),
          value = Parameters.Parameter.Value.Code(value),
        )
      is Date ->
        Parameters.Parameter(
          name = FhirString(value = "date"),
          value = Parameters.Parameter.Value.Date(value),
        )
      is Id ->
        Parameters.Parameter(
          name = FhirString(value = "id"),
          value = Parameters.Parameter.Value.Id(value),
        )
      is Instant ->
        Parameters.Parameter(
          name = FhirString(value = "instant"),
          value = Parameters.Parameter.Value.Instant(value),
        )
      is Markdown ->
        Parameters.Parameter(
          name = FhirString(value = "markdown"),
          value = Parameters.Parameter.Value.Markdown(value),
        )
      is Oid ->
        Parameters.Parameter(
          name = FhirString(value = "oid"),
          value = Parameters.Parameter.Value.Oid(value),
        )
      is PositiveInt ->
        Parameters.Parameter(
          name = FhirString(value = "positiveInt"),
          value = Parameters.Parameter.Value.PositiveInt(value),
        )
      is UnsignedInt ->
        Parameters.Parameter(
          name = FhirString(value = "unsignedInt"),
          value = Parameters.Parameter.Value.UnsignedInt(value),
        )
      is Uuid ->
        Parameters.Parameter(
          name = FhirString(value = "uuid"),
          value = Parameters.Parameter.Value.Uuid(value),
        )
      is Url ->
        Parameters.Parameter(
          name = FhirString(value = "url"),
          value = Parameters.Parameter.Value.Url(value),
        )
      is Uri ->
        Parameters.Parameter(
          name = FhirString(value = "uri"),
          value = Parameters.Parameter.Value.Uri(value),
        )
      is Address ->
        Parameters.Parameter(
          name = FhirString(value = "Address"),
          value = Parameters.Parameter.Value.Address(value),
        )
      is Age ->
        Parameters.Parameter(
          name = FhirString(value = "Age"),
          value = Parameters.Parameter.Value.Age(value),
        )
      is FhirAnnotation ->
        Parameters.Parameter(
          name = FhirString(value = "Annotation"),
          value = Parameters.Parameter.Value.Annotation(value),
        )
      is Attachment ->
        Parameters.Parameter(
          name = FhirString(value = "Attachment"),
          value = Parameters.Parameter.Value.Attachment(value),
        )
      is CodeableConcept ->
        Parameters.Parameter(
          name = FhirString(value = "CodeableConcept"),
          value = Parameters.Parameter.Value.CodeableConcept(value),
        )
      is Coding ->
        Parameters.Parameter(
          name = FhirString(value = "Coding"),
          value = Parameters.Parameter.Value.Coding(value),
        )
      is ContactPoint ->
        Parameters.Parameter(
          name = FhirString(value = "ContactPoint"),
          value = Parameters.Parameter.Value.ContactPoint(value),
        )
      is Count ->
        Parameters.Parameter(
          name = FhirString(value = "Count"),
          value = Parameters.Parameter.Value.Count(value),
        )
      is Distance ->
        Parameters.Parameter(
          name = FhirString(value = "Distance"),
          value = Parameters.Parameter.Value.Distance(value),
        )
      is Duration ->
        Parameters.Parameter(
          name = FhirString(value = "Duration"),
          value = Parameters.Parameter.Value.Duration(value),
        )
      is HumanName ->
        Parameters.Parameter(
          name = FhirString(value = "HumanName"),
          value = Parameters.Parameter.Value.HumanName(value),
        )
      is Identifier ->
        Parameters.Parameter(
          name = FhirString(value = "Identifier"),
          value = Parameters.Parameter.Value.Identifier(value),
        )
      is Money ->
        Parameters.Parameter(
          name = FhirString(value = "Money"),
          value = Parameters.Parameter.Value.Money(value),
        )
      is Period ->
        Parameters.Parameter(
          name = FhirString(value = "Period"),
          value = Parameters.Parameter.Value.Period(value),
        )
      is Quantity ->
        Parameters.Parameter(
          name = FhirString(value = "Quantity"),
          value = Parameters.Parameter.Value.Quantity(value),
        )
      is Range ->
        Parameters.Parameter(
          name = FhirString(value = "Range"),
          value = Parameters.Parameter.Value.Range(value),
        )
      is Ratio ->
        Parameters.Parameter(
          name = FhirString(value = "Ratio"),
          value = Parameters.Parameter.Value.Ratio(value),
        )
      is Reference ->
        Parameters.Parameter(
          name = FhirString(value = "Reference"),
          value = Parameters.Parameter.Value.Reference(value),
        )
      is SampledData ->
        Parameters.Parameter(
          name = FhirString(value = "SampledData"),
          value = Parameters.Parameter.Value.SampledData(value),
        )
      is Signature ->
        Parameters.Parameter(
          name = FhirString(value = "Signature"),
          value = Parameters.Parameter.Value.Signature(value),
        )
      is Timing ->
        Parameters.Parameter(
          name = FhirString(value = "Timing"),
          value = Parameters.Parameter.Value.Timing(value),
        )
      is ContactDetail ->
        Parameters.Parameter(
          name = FhirString(value = "ContactDetail"),
          value = Parameters.Parameter.Value.ContactDetail(value),
        )
      is Contributor ->
        Parameters.Parameter(
          name = FhirString(value = "Contributor"),
          value = Parameters.Parameter.Value.Contributor(value),
        )
      is DataRequirement ->
        Parameters.Parameter(
          name = FhirString(value = "DataRequirement"),
          value = Parameters.Parameter.Value.DataRequirement(value),
        )
      is Expression ->
        Parameters.Parameter(
          name = FhirString(value = "Expression"),
          value = Parameters.Parameter.Value.Expression(value),
        )
      is ParameterDefinition ->
        Parameters.Parameter(
          name = FhirString(value = "ParameterDefinition"),
          value = Parameters.Parameter.Value.ParameterDefinition(value),
        )
      is RelatedArtifact ->
        Parameters.Parameter(
          name = FhirString(value = "RelatedArtifact"),
          value = Parameters.Parameter.Value.RelatedArtifact(value),
        )
      is TriggerDefinition ->
        Parameters.Parameter(
          name = FhirString(value = "TriggerDefinition"),
          value = Parameters.Parameter.Value.TriggerDefinition(value),
        )
      is UsageContext ->
        Parameters.Parameter(
          name = FhirString(value = "UsageContext"),
          value = Parameters.Parameter.Value.UsageContext(value),
        )
      is Dosage ->
        Parameters.Parameter(
          name = FhirString(value = "Dosage"),
          value = Parameters.Parameter.Value.Dosage(value),
        )
      is Meta ->
        Parameters.Parameter(
          name = FhirString(value = "Meta"),
          value = Parameters.Parameter.Value.Meta(value),
        )
      // A resource is representable directly; the json-value extension below is for what is not.
      is Resource -> makeResourceParameter(name = fhirTypeName(value), resource = value)
      else ->
        Parameters.Parameter(
          extension =
            listOf(
              Extension(
                url = "http://fhir.forms-lab.com/StructureDefinition/json-value",
                value =
                  Extension.Value.String(
                    value =
                      FhirString(value = json.encodeToString(DynamicLookupSerializer(), value))
                  ),
              )
            ),
          name = FhirString(value = fhirTypeName(value)),
        )
    }
}
