package org.jarsi.betascout.data.db

import org.jarsi.betascout.domain.BetaObservation
import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.InstalledAppInfo
import org.jarsi.betascout.domain.UserBetaStatusInfo

fun InstalledAppEntity.toDomain() = InstalledAppInfo(
    packageName = packageName,
    label = label,
    versionName = versionName,
    versionCode = versionCode,
    installerPackage = installerPackage,
    isSystem = isSystem,
    lastScanned = lastScanned,
)

fun InstalledAppInfo.toEntity() = InstalledAppEntity(
    packageName = packageName,
    label = label,
    versionName = versionName,
    versionCode = versionCode,
    installerPackage = installerPackage,
    isSystem = isSystem,
    lastScanned = lastScanned,
)

fun BetaProgramEntity.toDomain() = BetaProgramInfo(
    packageName = packageName,
    appName = appName,
    testingUrl = testingUrl,
    knownStatus = knownStatus,
    productionVersionCode = productionVersionCode,
    notes = notes,
    source = source,
)

fun BetaProgramInfo.toEntity() = BetaProgramEntity(
    packageName = packageName,
    appName = appName,
    testingUrl = testingUrl,
    knownStatus = knownStatus,
    productionVersionCode = productionVersionCode,
    notes = notes,
    source = source,
)

fun BetaObservationEntity.toDomain() = BetaObservation(
    packageName = packageName,
    liveStatus = liveStatus,
    observedMembership = observedMembership,
    checkedAt = checkedAt,
    lastError = lastError,
)

fun BetaObservation.toEntity() = BetaObservationEntity(
    packageName = packageName,
    liveStatus = liveStatus,
    observedMembership = observedMembership,
    checkedAt = checkedAt,
    lastError = lastError,
)

fun UserBetaStatusEntity.toDomain() = UserBetaStatusInfo(
    packageName = packageName,
    state = state,
    watching = watching,
    reminderIntervalDays = reminderIntervalDays,
    lastCheckedByUser = lastCheckedByUser,
    userNote = userNote,
    lastRemindedAt = lastRemindedAt,
)

fun UserBetaStatusInfo.toEntity() = UserBetaStatusEntity(
    packageName = packageName,
    state = state,
    watching = watching,
    reminderIntervalDays = reminderIntervalDays,
    lastCheckedByUser = lastCheckedByUser,
    userNote = userNote,
    lastRemindedAt = lastRemindedAt,
)
