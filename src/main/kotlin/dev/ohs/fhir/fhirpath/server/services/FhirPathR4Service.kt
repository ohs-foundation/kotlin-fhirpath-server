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
import dev.ohs.fhir.fhirpath.forR4
import dev.ohs.fhir.fhirpath.server.DynamicLookupSerializer
import dev.ohs.fhir.fhirpath.server.toLocalTime
import dev.ohs.fhir.fhirpath.types.FhirPathDate
import dev.ohs.fhir.fhirpath.types.FhirPathDateTime
import dev.ohs.fhir.fhirpath.types.FhirPathQuantity
import dev.ohs.fhir.fhirpath.types.FhirPathTime
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.Age
import dev.ohs.fhir.model.r4.Annotation as FhirAnnotation
import dev.ohs.fhir.model.r4.Attachment
import dev.ohs.fhir.model.r4.Base64Binary
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.ContactDetail
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Contributor
import dev.ohs.fhir.model.r4.Count
import dev.ohs.fhir.model.r4.DataRequirement
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.Distance
import dev.ohs.fhir.model.r4.Dosage
import dev.ohs.fhir.model.r4.Duration
import dev.ohs.fhir.model.r4.Expression
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Id
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Instant
import dev.ohs.fhir.model.r4.Integer as FhirInteger
import dev.ohs.fhir.model.r4.Markdown
import dev.ohs.fhir.model.r4.Meta
import dev.ohs.fhir.model.r4.Money
import dev.ohs.fhir.model.r4.Oid
import dev.ohs.fhir.model.r4.ParameterDefinition
import dev.ohs.fhir.model.r4.Parameters
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.PositiveInt
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Range
import dev.ohs.fhir.model.r4.Ratio
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.RelatedArtifact
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.SampledData
import dev.ohs.fhir.model.r4.Signature
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Time
import dev.ohs.fhir.model.r4.Timing
import dev.ohs.fhir.model.r4.TriggerDefinition
import dev.ohs.fhir.model.r4.UnsignedInt
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.Url
import dev.ohs.fhir.model.r4.UsageContext
import dev.ohs.fhir.model.r4.Uuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

internal class FhirPathR4Service : FhirPathService<Parameters.Parameter, Resource>() {
  override val fhirVersion = "R4"
  private val resourceParser = Json

  override fun getFhirPathEngine() = FhirPathEngine.forR4()

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
