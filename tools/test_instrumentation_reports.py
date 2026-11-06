from pathlib import Path
import runpy
import tempfile
import unittest


verification = runpy.run_path(str(Path(__file__).with_name('verify-instrumentation.py')))
verify = verification['verify']


class InstrumentationReportTest(unittest.TestCase):
    def check(self, reports, exit_code=None, api_level=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, report in enumerate(reports):
                (root / f'TEST-{index}.xml').write_text(report)
            if exit_code is not None:
                (root / 'test-result-exit-code.txt').write_text(exit_code)
            return verify(root, api_level=api_level)

    def successful_report(self):
        cases = ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.InterfaceTest" name="case{i}"/>' for i in range(70))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.AuditRegressionDeviceTest" name="audit{i}"/>' for i in range(9))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.AccessRecoveryDeviceTest" name="access{i}"/>' for i in range(2))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.MediaFinalizationDeviceTest" name="finalization{i}"/>' for i in range(4))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.DownloadContinuityDeviceTest" name="continuity{i}"/>' for i in range(4))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.LargeAudioAndReuseDeviceTest" name="reuse{i}"/>' for i in range(10))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.TrackMetadataDeviceTest" name="metadata{i}"/>' for i in range(7))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.TrackMetadataUiDeviceTest" name="metadataUi{i}"/>' for i in range(3))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.MusicLibraryRedesignDeviceTest" name="redesign{i}"/>' for i in range(3))
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MusicArtistLoadingDeviceTest" name="timeout"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.TrackMetadataLookupViewModelDeviceTest" name="workflow"/>'
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.DuplicateResumeDeviceTest" name="completeRepeat{i}"/>' for i in range(2))
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateRemovalViewModelDeviceTest" name="workflow"/>'
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.MaintenanceBackgroundDeviceTest" name="background{i}"/>' for i in range(2))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.BackgroundCheckDeviceTest" name="job{i}"/>' for i in range(4))
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.ArtistFailureDeviceTest" name="diagnostics"/>'
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.FriendsMusicDeviceTest" name="friends{i}"/>' for i in range(6))
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.PlaybackDeviceTest" name="playback{i}"/>' for i in range(6))
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.ArtworkLookupViewModelDeviceTest" name="verifiedRenewalKeepsArtworkChoiceWhileLoginAndLogoutCannotWrite"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest" name="v3MigrationRetainsQueueAudioAndLegacyCursorAcrossReopen"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest" name="failedLegacyImportRollsBackAndKeepsItsOnlyOriginalCopy"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest" name="cursorAndIssuesRollbackTogetherAndStaleWritersCannotReplaceANewRun"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest" name="sqliteFullLeavesThePreviousCursorAndIssueSetRecoverable"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest" name="immutableTargetsAndManualIssueResolutionSurviveAccountScopedReopen"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest" name="maintenanceJournalUsesDatabaseIssuesAndRestoresThemAfterReload"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.MetadataCheckpointDeviceTest" name="checkpointPayloadScalesLinearlyFor1004500And20000Targets"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="legacyBackupAndAlbumKeysMigrateOnceAndSurviveReopen"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="failedLegacyImportRetainsOriginalAndRetriesWithoutPartialRows"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="malformedLegacyAndCorruptRowsNeverBecomeMatches"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="accountExpiryAndSnapshotChangesFenceStaleInstances"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="abortAndActualSQLiteFullKeepThePreviousReceiptAndCount"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="clearInvalidatesOtherInstancesWithoutTouchingExportQueueOrMedia"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="invalidSignaturesAndMissingAudioNeverCommitReceipts"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="receiptWritesAndReadsScaleFor1004500And20000"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.DuplicateReceiptDeviceTest" name="walReaderCompletesWhileAnotherConnectionHoldsTheWriter"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.UpdateCheckpointDeviceTest" name="malformedCheckpointAndOutOfDirectoryPathNeverDeleteAnUnrelatedFile"/>'
        cases += '<testcase classname="app.alcovaibe.vkmusicexport.UpdateCheckpointDeviceTest" name="corruptOwnedApkIsRemovedAndCanNeverRestoreReady"/>'
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.BulkDownloadDeviceTest" name="{name}"/>' for name in verification['BULK_DOWNLOAD_TESTS'])
        for suite, methods in verification['SAVED_PLAYBACK_TESTS'].items():
            cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.{suite}" name="{name}"/>' for name in methods)
        for suite, methods in verification['QUEUE_DOWNLOADS_TESTS'].items():
            cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.{suite}" name="{name}"/>' for name in methods)
        for suite, methods in verification['PLAYBACK_HISTORY_TESTS'].items():
            cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.{suite}" name="{name}"/>' for name in methods)
        cases += ''.join(f'<testcase classname="app.alcovaibe.vkmusicexport.SourceReadDeviceTest" name="{name}"/>' for name in verification['SOURCE_READ_TESTS'])
        return f'<testsuites tests="202" failures="0" errors="0" skipped="0"><testsuite>{cases}</testsuite></testsuites>'

    def test_successful_full_suite_is_accepted(self):
        self.assertEqual(self.check([self.successful_report()], '0'), 202)

    def test_missing_bulk_download_scenario_is_rejected_even_with_enough_other_tests(self):
        name=verification['BULK_DOWNLOAD_TESTS'][0]
        report=self.successful_report().replace(f'<testcase classname="app.alcovaibe.vkmusicexport.BulkDownloadDeviceTest" name="{name}"/>','')
        with self.assertRaisesRegex(ValueError, 'Required bulk download'):
            self.check([report], '0')

    def test_each_missing_saved_playback_case_is_rejected(self):
        for suite, methods in verification['SAVED_PLAYBACK_TESTS'].items():
            for name in methods:
                report=self.successful_report().replace(f'<testcase classname="app.alcovaibe.vkmusicexport.{suite}" name="{name}"/>','')
                with self.subTest(suite=suite, name=name), self.assertRaisesRegex(ValueError, 'Required saved/playback'):
                    self.check([report], '0')

    def test_each_missing_queue_downloads_case_is_rejected(self):
        for suite, methods in verification['QUEUE_DOWNLOADS_TESTS'].items():
            for name in methods:
                report=self.successful_report().replace(f'<testcase classname="app.alcovaibe.vkmusicexport.{suite}" name="{name}"/>','')
                with self.subTest(suite=suite,name=name),self.assertRaisesRegex(ValueError,'Required queue/downloads'):
                    self.check([report],'0')

    def test_every_playback_history_scenario_is_required(self):
        for suite, methods in verification['PLAYBACK_HISTORY_TESTS'].items():
            for name in methods:
                report=self.successful_report().replace(f'<testcase classname="app.alcovaibe.vkmusicexport.{suite}" name="{name}"/>','')
                with self.subTest(suite=suite,name=name),self.assertRaisesRegex(ValueError,'Required playback history'):
                    self.check([report],'0')

    def test_every_source_read_scenario_is_required(self):
        for name in verification['SOURCE_READ_TESTS']:
            report=self.successful_report().replace(f'<testcase classname="app.alcovaibe.vkmusicexport.SourceReadDeviceTest" name="{name}"/>','')
            with self.subTest(name=name),self.assertRaisesRegex(ValueError,'Required source read'):
                self.check([report],'0')

    def test_legacy_foreground_service_test_required_on_api_29_to_33(self):
        report = self.successful_report()
        for api in range(29, 34):
            with self.subTest(api=api), self.assertRaisesRegex(ValueError, 'Required legacy foreground'):
                self.check([report], '0', api_level=api)
            executed = report.replace('name="job0"',
                                      'name="legacyDuplicateForegroundServiceStartsFromVisibleActivityAndPausesWithoutCredentials"')
            self.assertEqual(self.check([executed], '0', api_level=api), 202)
        # UIDT-era devices do not need to run the legacy FGS test.
        self.assertEqual(self.check([report], '0', api_level=34), 202)

    def test_missing_report_after_installer_failure_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'No JUnit report'):
            self.check([])

    def test_runner_exit_code_is_checked_even_with_an_old_success_report(self):
        with self.assertRaisesRegex(ValueError, 'non-zero exit code'):
            self.check([self.successful_report()], '1')

    def test_zero_tests_is_not_success(self):
        with self.assertRaisesRegex(ValueError, 'Only 0'):
            self.check(['<testsuites tests="0"/>'])

    def test_failures_errors_and_skips_block_publication(self):
        for counter in ('failures', 'errors', 'skipped'):
            with self.subTest(counter=counter), self.assertRaisesRegex(ValueError, counter):
                self.check([f'<testsuites {counter}="1"/>'])

    def test_testcase_failure_is_not_hidden_by_summary_counters(self):
        with self.assertRaisesRegex(ValueError, 'failed or was skipped'):
            self.check(['<testsuite><testcase classname="C" name="n"><failure/></testcase></testsuite>'])

    def test_duplicate_reports_do_not_inflate_coverage(self):
        report = '<testsuite><testcase classname="C" name="n"/></testsuite>'
        with self.assertRaisesRegex(ValueError, 'Only 1'):
            self.check([report] * 72)

    def test_missing_interface_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.InterfaceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required interface'):
            self.check([report])

    def test_missing_audit_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.AuditRegressionDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required audit'):
            self.check([report])

    def test_missing_media_finalization_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.MediaFinalizationDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required media finalization'):
            self.check([report])


    def test_missing_download_continuity_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.DownloadContinuityDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required download continuity'):
            self.check([report])

    def test_missing_large_audio_and_reuse_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.LargeAudioAndReuseDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required large audio and reuse'):
            self.check([report])

    def test_missing_metadata_coverage_is_rejected(self):
        for name, reason in [('TrackMetadataDeviceTest', 'Required track metadata tests'),
                             ('TrackMetadataUiDeviceTest', 'Required track metadata interface')]:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, reason):
                self.check([self.successful_report().replace(name, 'OtherTest')])


    def test_missing_friends_profile_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.FriendsMusicDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required friends music'):
            self.check([report])

    def test_missing_background_job_coverage_is_rejected(self):
        report = self.successful_report().replace('app.alcovaibe.vkmusicexport.BackgroundCheckDeviceTest', 'OtherTest')
        with self.assertRaisesRegex(ValueError, 'Required background scan'):
            self.check([report])


    def test_missing_playback_coverage_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Required saved/playback'):
            self.check([self.successful_report().replace('app.alcovaibe.vkmusicexport.PlaybackDeviceTest', 'OtherTest')])

    def test_missing_artwork_session_integration_cannot_be_hidden_by_other_tests(self):
        report = self.successful_report().replace('verifiedRenewalKeepsArtworkChoiceWhileLoginAndLogoutCannotWrite', 'anotherTest')
        with self.assertRaisesRegex(ValueError, 'Required artwork lookup session'):
            self.check([report])

    def test_missing_metadata_migration_or_fault_injection_or_benchmark_is_rejected(self):
        for method in ('v3MigrationRetainsQueueAudioAndLegacyCursorAcrossReopen', 'failedLegacyImportRollsBackAndKeepsItsOnlyOriginalCopy', 'cursorAndIssuesRollbackTogetherAndStaleWritersCannotReplaceANewRun', 'sqliteFullLeavesThePreviousCursorAndIssueSetRecoverable', 'immutableTargetsAndManualIssueResolutionSurviveAccountScopedReopen', 'maintenanceJournalUsesDatabaseIssuesAndRestoresThemAfterReload', 'checkpointPayloadScalesLinearlyFor1004500And20000Targets'):
            with self.subTest(method=method), self.assertRaisesRegex(ValueError, 'Required metadata checkpoint'):
                self.check([self.successful_report().replace(method, 'anotherTest')])

    def test_every_receipt_migration_and_fault_case_is_required(self):
        for method in ['legacyBackupAndAlbumKeysMigrateOnceAndSurviveReopen', 'failedLegacyImportRetainsOriginalAndRetriesWithoutPartialRows', 'malformedLegacyAndCorruptRowsNeverBecomeMatches', 'accountExpiryAndSnapshotChangesFenceStaleInstances', 'abortAndActualSQLiteFullKeepThePreviousReceiptAndCount', 'clearInvalidatesOtherInstancesWithoutTouchingExportQueueOrMedia', 'invalidSignaturesAndMissingAudioNeverCommitReceipts', 'receiptWritesAndReadsScaleFor1004500And20000', 'walReaderCompletesWhileAnotherConnectionHoldsTheWriter']:
            with self.subTest(method=method), self.assertRaisesRegex(ValueError, 'Required duplicate receipt'):
                self.check([self.successful_report().replace(f'name="{method}"', 'name="missing_case"')], '0')


if __name__ == '__main__':
    unittest.main()
