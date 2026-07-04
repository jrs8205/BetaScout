package org.jarsi.betascout.data.db

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
    notes = notes,
    source = source,
)

fun BetaProgramInfo.toEntity() = BetaProgramEntity(
    packageName = packageName,
    appName = appName,
    testingUrl = testingUrl,
    knownStatus = knownStatus,
    notes = notes,
    source = source,
)

fun UserBetaStatusEntity.toDomain() = UserBetaStatusInfo(
    packageName = packageName,
    state = state,
    watching = watching,
    reminderIntervalDays = reminderIntervalDays,
    lastCheckedByUser = lastCheckedByUser,
    userNote = userNote,
)

fun UserBetaStatusInfo.toEntity() = UserBetaStatusEntity(
    packageName = packageName,
    state = state,
    watching = watching,
    reminderIntervalDays = reminderIntervalDays,
    lastCheckedByUser = lastCheckedByUser,
    userNote = userNote,
)
