package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCodeFlowTest {
    private val access=MusicAccess("vk1.a."+"SYNTHETIC_TOKEN_".repeat(8),"Fixture client")
    private fun session(deadline: Long=10000)=DeviceSession("ANONYMOUS_FIXTURE","PAIRING_FIXTURE","https://qr.vk.ru/s?q=FIXTURE",deadline)
    private fun code(value: String)=DeviceConfirmationCode.from(value)!!
    private abstract class AuthFixture: DeviceAuthApi {
        override suspend fun begin(deviceName: String): DeviceSession=error("The active request must be reused")
        override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation=error("Unexpected validation")
    }

    @Test fun codeFormatAndValidationResponsesAreStrictAndRedacted() {
        assertEquals("472819",code("472819").value)
        assertFalse(code("472819").toString().contains("472819"))
        for(bad in listOf("","12345","1234567"," 123456","123 456","123456\n","１２３４５６","abcdef","+79991234567"))
            assertNull(DeviceConfirmationCode.from(bad))
        for((status,result) in listOf(0 to DeviceValidation.ACCEPTED,1 to DeviceValidation.INCORRECT,2 to DeviceValidation.EXPIRED))
            assertEquals(result,DeviceAuthProtocol.validation(Json.parseToJsonElement("{\"status\":$status}")))
        for(raw in listOf("{}","[]","{\"status\":\"0\"}","{\"status\":false}","{\"status\":3}"))
            assertEquals(DeviceAuthIssue.RESPONSE,assertThrows(DeviceAuthFailure::class.java){
                DeviceAuthProtocol.validation(Json.parseToJsonElement(raw))
            }.issue)
    }

    @Test fun incorrectThenCorrectCodeReusesOneRequestAndWaitsForRealApproval()=runTest {
        val s=session();val codes=Channel<DeviceConfirmationCode>(1)
        var validations=0;var afterAcceptedPolls=0;var rejections=0;var phase=DeviceAuthPhase.READY
        val api=object: AuthFixture() {
            override suspend fun poll(session: DeviceSession): DeviceCheck {
                assertSame(s,session)
                return if(validations<2||afterAcceptedPolls++==0)DeviceCheck.VerificationRequired else DeviceCheck.Approved(access)
            }
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation {
                assertSame(s,session);validations++
                assertEquals(if(validations==1)"111111" else "472819",code.value)
                return if(validations==1)DeviceValidation.INCORRECT else DeviceValidation.ACCEPTED
            }
        }
        val result=async {awaitDeviceAccess(api,s,MutableStateFlow(true),MutableStateFlow(true),
            {testScheduler.currentTime},1000,codes,{rejections++}){phase=it}}
        runCurrent();assertEquals(DeviceAuthPhase.ENTER_CODE,phase)
        codes.send(code("111111"));runCurrent()
        assertEquals(1,rejections);assertEquals(DeviceAuthPhase.ENTER_CODE,phase);assertFalse(result.isCompleted)
        codes.send(code("472819"));runCurrent()
        assertEquals(2,validations);assertEquals(DeviceAuthPhase.AWAITING_APPROVAL,phase)
        assertFalse(result.isCompleted) // A successful validateAuthCode is not authentication success.
        advanceTimeBy(1000);runCurrent()
        assertSame(access,result.await());codes.cancel()
    }

    @Test fun declineIsDetectedWhileUserHasNotEnteredAnyCode()=runTest {
        var polls=0;var phase=DeviceAuthPhase.READY
        val api=object: AuthFixture(){override suspend fun poll(session: DeviceSession)=
            if(++polls==1)DeviceCheck.VerificationRequired else DeviceCheck.Declined}
        val codes=Channel<DeviceConfirmationCode>(1)
        val result=async {runCatching {awaitDeviceAccess(api,session(),MutableStateFlow(true),MutableStateFlow(true),
            {testScheduler.currentTime},1000,codes){phase=it}}}
        runCurrent();assertEquals(DeviceAuthPhase.ENTER_CODE,phase)
        advanceTimeBy(1000);runCurrent()
        assertEquals(DeviceAuthIssue.DECLINED,(result.await().exceptionOrNull() as DeviceAuthFailure).issue)
        assertEquals(2,polls);codes.cancel()
    }

    @Test fun backgroundSubmissionCannotSendValidationAndOriginalDeadlineKeepsRunning()=runTest {
        var polls=0;var validations=0
        val foreground=MutableStateFlow(true);val codes=Channel<DeviceConfirmationCode>(1)
        val api=object: AuthFixture() {
            override suspend fun poll(session: DeviceSession): DeviceCheck {polls++;return DeviceCheck.VerificationRequired}
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation {
                validations++;return DeviceValidation.ACCEPTED
            }
        }
        val result=async {runCatching {awaitDeviceAccess(api,session(5000),MutableStateFlow(true),foreground,
            {testScheduler.currentTime},1000,codes){}}}
        runCurrent();foreground.value=false;codes.send(code("472819"));runCurrent()
        assertEquals(0,validations);assertEquals(1,polls)
        advanceTimeBy(5000);runCurrent()
        assertEquals(DeviceAuthIssue.EXPIRED,(result.await().exceptionOrNull() as DeviceAuthFailure).issue)
        foreground.value=true;runCurrent();assertEquals(0,validations);codes.cancel()
    }

    @Test fun acceptedCodeCannotCompleteARequestThatNeverBecomesApproved()=runTest {
        var validations=0;var phase=DeviceAuthPhase.READY
        val codes=Channel<DeviceConfirmationCode>(1)
        val api=object: AuthFixture() {
            override suspend fun poll(session: DeviceSession)=DeviceCheck.VerificationRequired
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation {
                validations++;return DeviceValidation.ACCEPTED
            }
        }
        val result=async {runCatching {awaitDeviceAccess(api,session(3000),MutableStateFlow(true),MutableStateFlow(true),
            {testScheduler.currentTime},500,codes){phase=it}}}
        runCurrent();codes.send(code("472819"));runCurrent()
        assertEquals(DeviceAuthPhase.AWAITING_APPROVAL,phase);assertFalse(result.isCompleted)
        advanceTimeBy(3000);runCurrent()
        assertEquals(DeviceAuthIssue.EXPIRED,(result.await().exceptionOrNull() as DeviceAuthFailure).issue)
        assertEquals(1,validations);codes.cancel()
    }

    @Test fun cancellationDuringCodeValidationCancelsTheActualCoroutineAndDoesNotBecomeExpiry()=runTest {
        var validating=false;var finished=false
        val codes=Channel<DeviceConfirmationCode>(1)
        val api=object: AuthFixture() {
            override suspend fun poll(session: DeviceSession)=DeviceCheck.VerificationRequired
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation {
                validating=true
                try{awaitCancellation()}finally{finished=true}
            }
        }
        val result=async {awaitDeviceAccess(api,session(),MutableStateFlow(true),MutableStateFlow(true),
            {testScheduler.currentTime},1000,codes){}}
        runCurrent();codes.send(code("472819"));runCurrent();assertTrue(validating)
        result.cancel();runCurrent()
        assertTrue(result.isCancelled);assertTrue(finished);codes.cancel()
    }

    @Test fun validationExpiryIsTerminalAndApprovalAfterMonotonicDeadlineIsRejected()=runTest {
        val codes=Channel<DeviceConfirmationCode>(1)
        val expired=object: AuthFixture() {
            override suspend fun poll(session: DeviceSession)=DeviceCheck.VerificationRequired
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode)=DeviceValidation.EXPIRED
        }
        val result=async {runCatching {awaitDeviceAccess(expired,session(),MutableStateFlow(true),MutableStateFlow(true),
            {testScheduler.currentTime},1000,codes){}}}
        runCurrent();codes.send(code("472819"));runCurrent()
        assertEquals(DeviceAuthIssue.EXPIRED,(result.await().exceptionOrNull() as DeviceAuthFailure).issue)
        var clock=0L
        val late=object: AuthFixture(){override suspend fun poll(session: DeviceSession): DeviceCheck {
            clock=10001;return DeviceCheck.Approved(access)
        }}
        val failure=runCatching{awaitDeviceAccess(late,session(),MutableStateFlow(true),MutableStateFlow(true),{clock}){}}.exceptionOrNull()
        assertEquals(DeviceAuthIssue.EXPIRED,(failure as DeviceAuthFailure).issue);codes.cancel()
    }
}
