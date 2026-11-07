"""Reject green Gradle runs that never execute the Android instrumentation suite."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

BULK_DOWNLOAD_TESTS = (
    'analyzedLibraryAndAlbumGroupingQueueWithoutNewListRequestsAndSurviveReopen',
    'openedPlaylistUsesItsCompleteSnapshotWithoutRecheckingTheCatalog',
    'categoryReadsOnlyItsOwnCollectionsOnce',
    'expiredAnalysisReadsTheChangedLibraryOnce',
    'cancelledCategoryCannotReplaceQueueAndRetryReusesOnlyCompletedSources',
    'aNewLoginCannotReuseAnotherSessionAnalysisOrStartAQueue',
    'logoutDuringFreshPageReadCannotCommitThePreparedQueue',
)


SAVED_PLAYBACK_TESTS = {
    'SavedLibraryDeviceTest': (
        'allLegacySchemasPreserveQueueFilesAndCheckpointsAcrossMigration',
        'aliasesRetagRelinkAndRollbackPreservePhysicalDownloadOrder',
        'newPublicationAppearsBeforeBlockedPresencePassAndMissedEventsCatchUp',
        'providerBatchHintsKeepOwnershipSizeAndFullHashValidation',
        'indexedPagesAndAliasesScaleFor1004500And20000PhysicalFiles',
    ),
    'PartialPlaybackDeviceTest': (
        'ownSecondPageBarrierDoesNotBlockClickedDuplicateAndLaterFailureDoesNotMutateQueue',
        'friendSecondPageBarrierAllowsSearchResultAndKeepsViewerDifferentFromFriendOwner',
        'cachedOwnPageRemainsPlayableWhileRefreshWaitsForFirstResponse',
    ),
    'PlaybackDeviceTest': ('uncheckedSavedThirdTrackStartsWithoutVkLoginOrEnabledDownloadActions', 'lateQueuePreparationCannotReplaceLatestLocalChoice'),
}


QUEUE_DOWNLOADS_TESTS = {
    'QueueReadDeviceTest': (
        'v5MigrationPreservesTasksFilesFailuresAndCheckpointsAcrossReopen',
        'countersRevisionsAndDetailsRemainAtomicThroughRetryClearAndRollback',
        'progressDoesNotDecodeFailuresAndFirstPageDoesNotReadOffPagePayloads',
        'pagedErrorsAndFullExportKeepEveryTrackSourceAndDetail',
        'progressAndPageQueryCountsStayBoundedFor1004500And20000Failures',
    ),
    'DownloadsReaderDeviceTest': (
        'byteProgressDoesNotRefetchAndHiddenScreenStopsReading',
        'cancelledOlderQueuePageCannotReplaceTheNewQueue',
    ),
    'DownloadsScreenDeviceTest': (
        'downloadsReturnToSavedAndFriendsAndNotificationOpensTheSameQueue',
        'errorsAreReadByPageAndLargeTextKeepsTabsBackAndClearUsable',
    ),
    'PlaybackDeviceTest': ('realOfflinePlaybackContinuesThroughDownloadsAndBack',),
    'BackgroundCheckDeviceTest': ('pendingLegacyStartsArePromotedBeforeImmediatePauseWithoutRunningEitherQueue',),
}


PLAYBACK_HISTORY_TESTS = {
    'PlaybackHistoryDeviceTest': (
        'queueOccurrencesPositionAndShuffleOrderSurviveClosingEveryConnection',
        'replacementRejectsLateCursorAndConditionalClearWithoutTouchingTheNewQueue',
        'failedReplacementRollsBackEntriesOrderAndCursorTogether',
        'malformedQueueIsRejectedAndNeverDeletesTheExistingMusicDatabase',
        'coldRestoreAcceptsRenewalRejectsIndependentLoginAndKeepsPureOfflineQueue',
        'conflatedWriterFinishesWithTheLatestStopAndDoesNotResurrectAnOlderQueue',
        'cursorWritesStayConstantFor1004500And20000Entries',
    ),
    'PlaybackDeviceTest': ('serviceRecreationRetainsPausedQueueModesAndAllowsAnotherSelectedPosition',
        'artworkMetadataUpdateDoesNotRepublishTheChildTimelineOrResolveAgain',
        'currentSessionStopFromControllersNotificationAndHardwareStillAllowsExplicitResume',
        'oldNotificationIdentityCannotTargetARecreatedPlaybackService'),
}


SOURCE_READ_TESTS = (
    'ownPlaylistOpensAndReopensWithoutGlobalCatalogOrRepeatedPages',
    'expiredOwnPlaylistQueuesOnlyItsPagesWithoutReloadingAnyCatalog',
    'friendSingleTrackPreemptsAnIncompleteReadAndPreservesTheDisplayedOccurrence',
    'friendPlaylistSelectedAndBulkQueuesReuseTheirSnapshotWithoutCatalogReads',
    'expiredFriendBulkSourceUpdatesItsCountWithoutRecheckingOtherSources',
    'newLoginAndLogoutDuringOwnerProofCannotReplaceTheQueue',
    'cancellationOfABulkReadNeverCommitsItsPartialQueue',
    'passiveRefreshReusesMetadataExplicitRefreshFetchesAndKeepsOwnLibraryCache',
    'inaccessibleFreshProfileCannotExposeCachedFriendMusic',
    'anEmptyRefreshedSourceDoesNotEraseThePreviousQueueOrStartAJob',
)


FRIEND_PREVIEW_TESTS = (
    'pauseCancelsBothProbesAndReturnReusesOnlyConfirmedVisibleResults',
    'profileAndHiddenDestinationStopProbesAndBackRestartsTheVisibleList',
    'logoutAndNewLoginCannotPublishAnOldDenialOrReuseItsCache',
)


def verify(results: Path, minimum_tests: int = 136, api_level: int | None = None) -> int:
    for code in results.rglob('test-result-exit-code.txt'):
        if code.read_text().strip() != '0':
            raise ValueError('Android test runner returned a non-zero exit code')
    reports = list(results.rglob('TEST-*.xml'))
    if not reports:
        raise ValueError('No JUnit report: instrumentation did not complete; check APK installation and emulator logs')
    executed = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in root.iter():
            if suite.tag in ('testsuites', 'testsuite'):
                for counter in ('failures', 'errors', 'skipped'):
                    if int(suite.get(counter, '0')):
                        raise ValueError(f'Instrumentation report contains {counter}')
        for case in root.iter('testcase'):
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Instrumentation testcase failed or was skipped')
            identity = (case.get('classname'), case.get('name'))
            if not all(identity):
                raise ValueError('Instrumentation testcase is missing its identity')
            executed.add(identity)
    if len(executed) < minimum_tests:
        raise ValueError(f'Only {len(executed)} instrumentation tests executed; expected at least {minimum_tests}')
    for method in BULK_DOWNLOAD_TESTS:
        if ('app.alcovaibe.vkmusicexport.BulkDownloadDeviceTest', method) not in executed:
            raise ValueError(f'Required bulk download integration test did not execute: {method}')
    for suite, methods in SAVED_PLAYBACK_TESTS.items():
        for method in methods:
            if (f'app.alcovaibe.vkmusicexport.{suite}', method) not in executed:
                raise ValueError(f'Required saved/playback integration test did not execute: {suite}.{method}')
    for suite, methods in QUEUE_DOWNLOADS_TESTS.items():
        for method in methods:
            if (f'app.alcovaibe.vkmusicexport.{suite}', method) not in executed:
                raise ValueError(f'Required queue/downloads integration test did not execute: {suite}.{method}')
    for suite, methods in PLAYBACK_HISTORY_TESTS.items():
        for method in methods:
            if (f'app.alcovaibe.vkmusicexport.{suite}', method) not in executed:
                raise ValueError(f'Required playback history test did not execute: {suite}.{method}')
    for method in SOURCE_READ_TESTS:
        if ('app.alcovaibe.vkmusicexport.SourceReadDeviceTest', method) not in executed:
            raise ValueError(f'Required source read test did not execute: {method}')
    for method in FRIEND_PREVIEW_TESTS:
        if ('app.alcovaibe.vkmusicexport.FriendPreviewLifecycleDeviceTest', method) not in executed:
            raise ValueError(f'Required friend preview lifecycle test did not execute: {method}')
    if not any(name == 'app.alcovaibe.vkmusicexport.InterfaceTest' for name, _ in executed):
        raise ValueError('Required interface test did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.AuditRegressionDeviceTest' for name, _ in executed) < 9:
        raise ValueError('Required audit regression tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.AccessRecoveryDeviceTest' for name, _ in executed) < 2:
        raise ValueError('Required access recovery tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.MediaFinalizationDeviceTest' for name, _ in executed) < 4:
        raise ValueError('Required media finalization tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.DownloadContinuityDeviceTest' for name, _ in executed) < 4:
        raise ValueError('Required download continuity tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.LargeAudioAndReuseDeviceTest' for name, _ in executed) < 10:
        raise ValueError('Required large audio and reuse tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.TrackMetadataDeviceTest' for name, _ in executed) < 7:
        raise ValueError('Required track metadata tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.TrackMetadataUiDeviceTest' for name, _ in executed) < 3:
        raise ValueError('Required track metadata interface test did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.MusicLibraryRedesignDeviceTest' for name, _ in executed) < 3:
        raise ValueError('Required music library redesign tests did not execute')
    if not any(name == 'app.alcovaibe.vkmusicexport.MusicArtistLoadingDeviceTest' for name, _ in executed):
        raise ValueError('Required artist loading test did not execute')
    if not any(name == 'app.alcovaibe.vkmusicexport.TrackMetadataLookupViewModelDeviceTest' for name, _ in executed):
        raise ValueError('Required metadata lookup workflow test did not execute')
    if not any(name == 'app.alcovaibe.vkmusicexport.DuplicateRemovalViewModelDeviceTest' for name, _ in executed):
        raise ValueError('Required duplicate removal workflow test did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.MaintenanceBackgroundDeviceTest' for name, _ in executed) < 2:
        raise ValueError('Required file maintenance background and restoration tests did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.BackgroundCheckDeviceTest' for name, _ in executed) < 5:
        raise ValueError('Required background scan and user-initiated job notification tests did not execute')
    if api_level is not None and 29 <= api_level <= 33:
        required = ('app.alcovaibe.vkmusicexport.BackgroundCheckDeviceTest',
                    'legacyDuplicateForegroundServiceStartsFromVisibleActivityAndPausesWithoutCredentials')
        if required not in executed:
            raise ValueError(f'Required legacy foreground service integration test missing on API {api_level}')
    if not any(name == 'app.alcovaibe.vkmusicexport.ArtistFailureDeviceTest' for name, _ in executed):
        raise ValueError('Required artist failure diagnostics test did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.FriendsMusicDeviceTest' for name, _ in executed) < 6:
        raise ValueError('Required friends music profile, access and queue tests did not execute')
    if ('app.alcovaibe.vkmusicexport.FriendsMusicDeviceTest',
            'leavingFriendsDestinationCancelsVisiblePreviewsAndReturningResumesThem') not in executed:
        raise ValueError('Required friend destination lifecycle test did not execute')
    if sum(name == 'app.alcovaibe.vkmusicexport.PlaybackDeviceTest' for name, _ in executed) < 6:
        raise ValueError('Required background playback, controls and player interface tests did not execute')
    required = ('app.alcovaibe.vkmusicexport.ArtworkLookupViewModelDeviceTest',
                'verifiedRenewalKeepsArtworkChoiceWhileLoginAndLogoutCannotWrite')
    if required not in executed:
        raise ValueError('Required artwork lookup session integration test did not execute')
    metadata_required = ('v3MigrationRetainsQueueAudioAndLegacyCursorAcrossReopen', 'failedLegacyImportRollsBackAndKeepsItsOnlyOriginalCopy', 'cursorAndIssuesRollbackTogetherAndStaleWritersCannotReplaceANewRun', 'sqliteFullLeavesThePreviousCursorAndIssueSetRecoverable', 'immutableTargetsAndManualIssueResolutionSurviveAccountScopedReopen', 'maintenanceJournalUsesDatabaseIssuesAndRestoresThemAfterReload', 'checkpointPayloadScalesLinearlyFor1004500And20000Targets')
    for method in metadata_required:
        if ('app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest', method) not in executed:
            raise ValueError(f'Required metadata checkpoint integration test did not execute: {method}')
    receipt_required = ('legacyBackupAndAlbumKeysMigrateOnceAndSurviveReopen', 'failedLegacyImportRetainsOriginalAndRetriesWithoutPartialRows', 'malformedLegacyAndCorruptRowsNeverBecomeMatches', 'accountExpiryAndSnapshotChangesFenceStaleInstances', 'abortAndActualSQLiteFullKeepThePreviousReceiptAndCount', 'clearInvalidatesOtherInstancesWithoutTouchingExportQueueOrMedia', 'invalidSignaturesAndMissingAudioNeverCommitReceipts', 'receiptWritesAndReadsScaleFor1004500And20000', 'walReaderCompletesWhileAnotherConnectionHoldsTheWriter')
    for method in receipt_required:
        if ('app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest', method) not in executed:
            raise ValueError(f'Required duplicate receipt integration test did not execute: {method}')
    for method in ('malformedCheckpointAndOutOfDirectoryPathNeverDeleteAnUnrelatedFile', 'corruptOwnedApkIsRemovedAndCanNeverRestoreReady'):
        if ('app.alcovaibe.vkmusicexport.UpdateCheckpointDeviceTest', method) not in executed:
            raise ValueError(f'Required updater checkpoint test did not execute: {method}')
    return len(executed)


if __name__ == '__main__':
    try:
        count = verify(Path(sys.argv[1]), api_level=int(sys.argv[2]) if len(sys.argv) > 2 else None)
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit(f'Instrumentation verification failed: {error}')
    print(f'Instrumentation verification passed: {count} distinct tests executed, no failures or skips.')
