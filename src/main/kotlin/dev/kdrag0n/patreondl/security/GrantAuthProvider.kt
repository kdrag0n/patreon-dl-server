package dev.kdrag0n.patreondl.security

import dev.kdrag0n.patreondl.config.Config
import dev.kdrag0n.patreondl.data.Grant
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.codec.binary.Base64
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.ktor.ext.inject
import java.time.Instant

private const val CHALLENGE_KEY = "GrantAuthChallenge"

fun Application.installGrantAuthProvider() {
    val config: Config by inject()

    authentication {
        grants("grantLinks") {
            grantKey = hex(config.web.grantKey)
        }
    }
}

@Serializable
data class GrantInfo(
    val grantId: Int,
) {
    companion object {
        suspend fun generateUrl(
            call: ApplicationCall,
            encrypter: AuthenticatedEncrypter,
            tag: String,
            durationHours: Float,
        ): String {
            val durationMs = (durationHours * 60 * 60 * 1000).toLong()
            val grant = newSuspendedTransaction(Dispatchers.IO) {
                Grant.new {
                    path = call.request.path()
                    this.tag = tag
                    expireTime = Instant.now().plusMillis(durationMs)
                }
            }

            val grantInfo = GrantInfo(
                grantId = grant.id.value,
            )

            // Pad to nearest 16-byte boundary to avoid side-channel attacks
            var grantJson = Json.encodeToString(grantInfo)
            grantJson += " ".repeat(grantJson.length % 16)
            // Encrypt padded JSON data
            val grantData = Base64.encodeBase64String(encrypter.encrypt(grantJson.encodeToByteArray()))

            return call.url {
                parameters.clear()
                parameters["grant"] = grantData
            }
        }
    }
}

private class GrantAuthenticationProvider(
    config: Configuration
) : AuthenticationProvider(config) {
    val encrypter = AuthenticatedEncrypter(config.grantKey)

    suspend fun validateGrant(path: String, grantData: String?): Pair<Grant?, AuthenticationFailedCause?> {
        if (grantData == null) {
            return null to AuthenticationFailedCause.NoCredentials
        }

        // Get reference info
        val info = try {
            val encData = Base64.decodeBase64(grantData)
            val json = encrypter.decrypt(encData).decodeToString()
            Json.decodeFromString<GrantInfo>(json)
        } catch (e: Exception) {
            return null to AuthenticationFailedCause.InvalidCredentials
        }

        // Get actual grant from database
        val finalGrant = newSuspendedTransaction {
            val grant = newSuspendedTransaction {
                Grant.findById(info.grantId)
            } ?: return@newSuspendedTransaction null

            if (grant.path != path || Instant.now() > grant.expireTime) {
                return@newSuspendedTransaction null
            }

            grant.accessCount++
            grant.lastAccessTime = Instant.now()

            return@newSuspendedTransaction grant
        }

        return if (finalGrant == null) {
            null to AuthenticationFailedCause.InvalidCredentials
        } else {
            finalGrant to null
        }
    }

    class Configuration(name: String) : AuthenticationProvider.Configuration(name) {
        var grantKey = ByteArray(0)
    }
}

private fun Authentication.Configuration.grants(
    name: String,
    configure: GrantAuthenticationProvider.Configuration.() -> Unit
) {
    val provider = GrantAuthenticationProvider(GrantAuthenticationProvider.Configuration(name).apply(configure))

    provider.pipeline.intercept(AuthenticationPipeline.RequestAuthentication) { context ->
        val (grant, failedCause) = provider.validateGrant(
            call.request.path(),
            call.request.queryParameters["grant"],
        )

        if (grant == null) {
            return@intercept context.challenge(CHALLENGE_KEY, failedCause!!) {
                call.respond(UnauthorizedResponse())
                it.complete()
            }
        }

        context.principal(grant)
    }

    register(provider)
}