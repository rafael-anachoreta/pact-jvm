package au.com.dius.pact.core.model.messaging

import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.ProviderState
import au.com.dius.pact.core.model.generators.Generators
import au.com.dius.pact.core.model.matchingrules.MatchingRules
import au.com.dius.pact.core.model.matchingrules.MatchingRulesImpl
import au.com.dius.pact.core.support.Json
import au.com.dius.pact.core.support.isNotEmpty
import au.com.dius.pact.core.support.json.JsonException
import au.com.dius.pact.core.support.json.JsonParser
import au.com.dius.pact.core.support.json.JsonValue
import mu.KLogging
import org.apache.commons.codec.binary.Base64
import org.apache.commons.lang3.StringUtils
import org.apache.http.entity.ContentType

/**
 * Message in a Message Pact
 */
class Message @JvmOverloads constructor(
  override val description: String,
  override val providerStates: List<ProviderState> = listOf(),
  var contents: OptionalBody = OptionalBody.missing(),
  var matchingRules: MatchingRules = MatchingRulesImpl(),
  var generators: Generators = Generators(),
  var metaData: MutableMap<String, Any?> = mutableMapOf(),
  override val interactionId: String? = null
) : Interaction {

  fun contentsAsBytes() = contents.orEmpty()

  fun contentsAsString() = contents.valueAsString()

  fun getContentType() = if (contents.isPresent() && contents.contentType.contentType.isNotEmpty()) {
    contents.contentType.contentType
  } else {
    contentType(metaData)
  }

  @Deprecated("Use the content type associated with the message body")
  fun getParsedContentType() = parseContentType(this.getContentType() ?: "")

  override fun toMap(pactSpecVersion: PactSpecVersion): Map<String, Any?> {
    val map: MutableMap<String, Any?> = mutableMapOf(
      "description" to description,
      "metaData" to metaData
    )
    if (!contents.isMissing()) {
      map["contents"] = when {
        isJsonContents() -> {
          try {
            val json = JsonParser.parseString(contents.valueAsString())
            if (json is JsonValue.StringValue) {
              contents.valueAsString()
            } else {
              Json.fromJson(json)
            }
          } catch (ex: JsonException) {
            logger.trace(ex) { "Failed to parse JSON body" }
            contents.valueAsString()
          }
        }
        else -> formatContents()
      }
    }
    if (providerStates.isNotEmpty()) {
      map["providerStates"] = providerStates.map { it.toMap() }
    }
    if (matchingRules.isNotEmpty()) {
      map["matchingRules"] = matchingRules.toMap(pactSpecVersion)
    }
    if (generators.isNotEmpty()) {
      map["generators"] = generators.toMap(pactSpecVersion)
    }
    return map
  }

  private fun isJsonContents(): Boolean {
    return if (contents.isPresent()) {
      val contentType = contentType(metaData)
      if (contentType.isNotEmpty()) {
        isJson(contentType)
      } else {
        isJson(contents.contentType.asMimeType())
      }
    } else {
      false
    }
  }

  fun formatContents(): String {
    return if (contents.isPresent()) {
      val contentType = contentType(metaData) ?: contents.contentType.asMimeType()
      when {
        isJson(contentType) -> Json.gsonPretty.toJson(JsonParser.parseString(contents.valueAsString()).toGson())
        isOctetStream(contentType) -> Base64.encodeBase64String(contentsAsBytes())
        else -> contents.valueAsString()
      }
    } else {
      ""
    }
  }

  override fun uniqueKey(): String {
    return StringUtils.defaultIfEmpty(providerStates.joinToString { it.name.toString() }, "None") +
      "_$description"
  }

  override fun conflictsWith(other: Interaction) = other !is Message

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as Message

    if (description != other.description) return false
    if (providerStates != other.providerStates) return false
    if (contents != other.contents) return false
    if (matchingRules != other.matchingRules) return false
    if (generators != other.generators) return false

    return true
  }

  override fun hashCode(): Int {
    var result = description.hashCode()
    result = 31 * result + providerStates.hashCode()
    result = 31 * result + contents.hashCode()
    result = 31 * result + matchingRules.hashCode()
    result = 31 * result + generators.hashCode()
    return result
  }

  override fun toString(): String {
    return "Message(description='$description', providerStates=$providerStates, contents=$contents, " +
      "matchingRules=$matchingRules, generators=$generators, metaData=$metaData)"
  }

  fun withMetaData(metadata: Map<String, Any>): Message {
    this.metaData = metadata.toMutableMap()
    return this
  }

  companion object : KLogging() {
    const val JSON = "application/json"
    const val TEXT = "text/plain"

    /**
     * Builds a message from a Map
     */
    @JvmStatic
    fun fromJson(json: JsonValue.Object): Message {
      val providerStates = when {
        json.has("providerStates") -> json["providerStates"].asArray().values.map { ProviderState.fromJson(it) }
        json.has("providerState") -> listOf(ProviderState(Json.toString(json["providerState"])))
        else -> listOf()
      }

      val metaData = if (json.has("metaData"))
        json["metaData"].asObject().entries.entries.associate { it.key to Json.fromJson(it.value) }
      else
        emptyMap()

      val contentType = au.com.dius.pact.core.model.ContentType(contentType(metaData))
      val contents = if (json.has("contents")) {
        when (val contents = json["contents"]) {
          is JsonValue.Null -> OptionalBody.nullBody()
          is JsonValue.StringValue -> OptionalBody.body(contents.value.toByteArray(contentType.asCharset()),
            contentType)
          else -> OptionalBody.body(contents.serialise().toByteArray(contentType.asCharset()), contentType)
        }
      } else {
        OptionalBody.missing()
      }
      val matchingRules = if (json.has("matchingRules"))
        MatchingRulesImpl.fromJson(json["matchingRules"])
      else MatchingRulesImpl()
      val generators = if (json.has("generators"))
        Generators.fromJson(json["generators"])
      else Generators()

      return Message(Json.toString(json["description"]), providerStates,
        contents, matchingRules, generators, metaData.toMutableMap(), Json.toString(json["_id"]))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun parseContentType(contentType: String?): ContentType? {
      return if (contentType.isNotEmpty()) {
        try {
          ContentType.parse(contentType)
        } catch (e: RuntimeException) {
          logger.debug(e) { "Failed to parse content type '$contentType'" }
          null
        }
      } else {
        null
      }
    }

    fun contentType(metaData: Map<String, Any?>): String? {
      return parseContentType(metaData.entries.find {
        it.key.toLowerCase() == "contenttype" || it.key.toLowerCase() == "content-type"
      }?.value?.toString())?.mimeType
    }

    private fun isJson(contentType: String?) =
      contentType != null && contentType.matches(Regex("application/.*json"))

    private fun isOctetStream(contentType: String?) = contentType == "application/octet-stream"
  }
}
