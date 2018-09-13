package com.supercilex.robotscouter.server.functions

import com.supercilex.robotscouter.common.FIRESTORE_ACTIVE_TOKENS
import com.supercilex.robotscouter.common.FIRESTORE_MEDIA
import com.supercilex.robotscouter.common.FIRESTORE_METRICS
import com.supercilex.robotscouter.common.FIRESTORE_NAME
import com.supercilex.robotscouter.common.FIRESTORE_NUMBER
import com.supercilex.robotscouter.common.FIRESTORE_OWNERS
import com.supercilex.robotscouter.common.FIRESTORE_PREV_UID
import com.supercilex.robotscouter.common.FIRESTORE_REF
import com.supercilex.robotscouter.common.FIRESTORE_SCOUTS
import com.supercilex.robotscouter.common.FIRESTORE_TEAMS
import com.supercilex.robotscouter.common.FIRESTORE_TIMESTAMP
import com.supercilex.robotscouter.common.FIRESTORE_TOKEN
import com.supercilex.robotscouter.common.FIRESTORE_WEBSITE
import com.supercilex.robotscouter.common.isPolynomial
import com.supercilex.robotscouter.server.utils.batch
import com.supercilex.robotscouter.server.utils.duplicateTeams
import com.supercilex.robotscouter.server.utils.firestore
import com.supercilex.robotscouter.server.utils.teams
import com.supercilex.robotscouter.server.utils.toMap
import com.supercilex.robotscouter.server.utils.types.CallableContext
import com.supercilex.robotscouter.server.utils.types.Change
import com.supercilex.robotscouter.server.utils.types.DeltaDocumentSnapshot
import com.supercilex.robotscouter.server.utils.types.DocumentSnapshot
import com.supercilex.robotscouter.server.utils.types.FieldValues
import com.supercilex.robotscouter.server.utils.types.HttpsError
import com.supercilex.robotscouter.server.utils.types.SetOptions
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.asPromise
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.await
import kotlinx.coroutines.experimental.awaitAll
import kotlin.js.Date
import kotlin.js.Json
import kotlin.js.Promise
import kotlin.js.json

fun updateOwners(data: Json, context: CallableContext): Promise<*>? {
    val auth = context.auth
    val token = data[FIRESTORE_TOKEN] as? String
    val path = data[FIRESTORE_REF] as? String
    val prevUid = data[FIRESTORE_PREV_UID]

    if (auth == null) throw HttpsError("unauthenticated")
    if (token == null || path == null) throw HttpsError("invalid-argument")
    if (prevUid != null) {
        if (prevUid !is String) {
            throw HttpsError("invalid-argument")
        } else if (prevUid == auth.uid) {
            throw HttpsError("already-exists", "Cannot add and remove the same user")
        }
    }
    prevUid as String?

    val isTeam = path.contains(FIRESTORE_TEAMS)
    val value = run {
        val number = data[FIRESTORE_NUMBER] as? Number
        val timestamp = data[FIRESTORE_TIMESTAMP] as? Number

        @Suppress("IMPLICIT_CAST_TO_ANY")
        when {
            number != null -> number
            timestamp != null -> Date(timestamp)
            else -> throw HttpsError("invalid-argument")
        }
    }

    val ref = firestore.doc(path)
    val oldOwnerPath = prevUid?.let { "$FIRESTORE_OWNERS.$it" }
    val newOwnerPath = "$FIRESTORE_OWNERS.${auth.uid}"

    return GlobalScope.async {
        val content = ref.get().await()

        if (!content.exists) throw HttpsError("not-found")
        if (content.get<Json>(FIRESTORE_ACTIVE_TOKENS)[token] == null) {
            throw HttpsError("permission-denied", "Token $token is invalid for $path")
        }

        firestore.batch {
            oldOwnerPath?.let {
                update(ref, it, FieldValues.delete())
                if (isTeam) {
                    set(duplicateTeams.doc(prevUid),
                        json(ref.id to FieldValues.delete()),
                        SetOptions.merge)
                }
            }

            update(ref, newOwnerPath, value)
            if (isTeam) {
                set(duplicateTeams.doc(auth.uid),
                    json(ref.id to content.get(FIRESTORE_NUMBER)),
                    SetOptions.merge)
            }
        }
    }.asPromise()
}

fun mergeDuplicateTeams(event: Change<DeltaDocumentSnapshot>): Promise<*>? {
    val snapshot = event.after
    val uid = snapshot.id
    console.log("Checking for duplicate teams for $uid.")

    if (!snapshot.exists) return null
    val duplicates = snapshot.data().toMap<Long>().toList()
            .groupBy { (_, number) -> number }
            .mapValues { (_, duplicates) -> duplicates.map { (teamId) -> teamId } }
            .filter { (number) -> number.asDynamic() >= 0 } // Exclude trashed teams
            .filter { (_, ids) -> ids.isPolynomial }
            .onEach { console.log("Found duplicates: $it") }
            .map { (_, ids) -> ids }
    if (duplicates.isEmpty()) {
        console.log("No duplicates found.")
        return null
    }

    return GlobalScope.async {
        duplicates.map { ids ->
            @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
            inner@ async {
                val teamData = ids.map { teams.doc(it) }
                        .map { async { it.get().await() } }
                        .awaitAll()

                if (teamData.any { !it.exists }) return@inner

                val teams = teamData
                        .associate { it to it.ref.collection(FIRESTORE_SCOUTS).get() }
                        .mapValues { (_, scout) -> scout.await().docs }
                        .mapValues { (_, scouts) ->
                            scouts.associate { it to it.ref.collection(FIRESTORE_METRICS).get() }
                                    .mapValues { (_, metric) -> metric.await().docs }
                        }
                        .toList()
                        .sortedBy { (team) -> team.get<Date>(FIRESTORE_TIMESTAMP).getTime() }

                val (keep) = teams.first()
                val merges = teams.subList(1, teams.size)

                val oldKeepData = keep.data().toMap<Any?>()
                val newKeepData = oldKeepData.toMutableMap()
                val newKeepOwners =
                        (newKeepData[FIRESTORE_OWNERS] as Json).toMap<Long>().toMutableMap()

                for ((merge, scouts) in merges) {
                    val mergeData = merge.data().toMap<Any?>()

                    fun mergeValue(name: String) {
                        if (newKeepData[name] == null) newKeepData[name] = mergeData[name]
                    }
                    mergeValue(FIRESTORE_NAME)
                    mergeValue(FIRESTORE_MEDIA)
                    mergeValue(FIRESTORE_WEBSITE)
                    newKeepOwners.putAll((mergeData[FIRESTORE_OWNERS] as Json).toMap())

                    firestore.batch {
                        for ((scout, metrics) in scouts) {
                            console.log("Copying scout ${scout.ref.path} into team ${keep.id}.")

                            val ref = keep.ref.collection(FIRESTORE_SCOUTS).doc(scout.id)
                            set(ref, scout.data())
                            for (metric in metrics) {
                                set(ref.collection(FIRESTORE_METRICS).doc(metric.id),
                                    metric.data())
                            }
                        }
                    }
                }

                newKeepData[FIRESTORE_OWNERS] = json(*newKeepOwners.toList().toTypedArray())
                console.log("Updating team to\n$newKeepData\n\nfrom\n$oldKeepData")
                keep.ref.set(json(*newKeepData.toList().toTypedArray())).await()

                for ((merge) in merges) deleteTeam(merge)
            }
        }.awaitAll()
    }.asPromise()
}

// TODO remove after v3.0 ships
fun mergeDuplicateTeamsCompat(team: DocumentSnapshot) = GlobalScope.async {
    val uid = team.get<Json>(FIRESTORE_OWNERS).toMap<Long>().map { it.key }.single()

    duplicateTeams.doc(uid)
            .set(json(team.id to team.get(FIRESTORE_NUMBER)), SetOptions.merge)
            .await()
}.asPromise()
