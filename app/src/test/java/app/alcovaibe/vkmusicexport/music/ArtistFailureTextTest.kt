package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class ArtistFailureTextTest {
    @Test fun missingSectionIsNotDescribedAsAnApiDenial() {
        val error=ArtistLoadingFailure(ArtistFailureReason.SUBSCRIPTIONS_NOT_FOUND,MusicError.METHOD,pages=2,sections=3,blocks=9,pool=4)
        val text=ArtistFailureText.describe(error)
        assertTrue(text.contains("раздел подписок"));assertTrue(text.contains("не подтверждённый отказ"))
        assertTrue(text.contains("страниц: 2"));assertTrue(text.contains("разделов: 3"));assertTrue(text.contains("блоков: 9"))
        assertFalse(text.contains("ВК вернул ошибку API"))
    }
    @Test fun explicitVkCodeAndMethodArePreservedSeparatelyFromHttp() {
        val error=ArtistLoadingFailure(ArtistFailureReason.REQUEST,MusicError.DENIED,"catalog.getSection",failure=MusicFailure(MusicError.DENIED,15))
        val text=ArtistFailureText.describe(error)
        assertTrue(text.contains("catalog.getSection"));assertTrue(text.contains("API 15"));assertTrue(text.contains("запрещён"))
        val http=ArtistFailureText.describe(ArtistLoadingFailure(ArtistFailureReason.REQUEST,MusicError.ACCESS,"catalog.getAudio",failure=MusicFailure(MusicError.ACCESS,httpStatus=403)))
        assertTrue(http.contains("HTTP 403"));assertFalse(http.contains("API 15"))
    }
    @Test fun incompleteCatalogueAndNetworkFailuresHaveDifferentReasons() {
        val loop=ArtistFailureText.describe(ArtistLoadingFailure(ArtistFailureReason.CURSOR_LOOP,MusicError.INCOMPLETE))
        assertTrue(loop.contains("повторил уже прочитанную"))
        val network=ArtistFailureText.describe(ArtistLoadingFailure(ArtistFailureReason.REQUEST,MusicError.NETWORK,"catalog.getAudio",failure=MusicFailure(MusicError.NETWORK,kind=MusicFailureKind.DNS)))
        assertTrue(network.contains("DNS"));assertTrue(network.contains("catalog.getAudio"));assertFalse(network.contains("подписок не найден"))
    }
    @Test fun untrustedExceptionTextNeverBecomesUiText() {
        val secret="vk1.a.PRIVATE_TOKEN and https://private.test/user?secret=123"
        assertFalse(ArtistFailureText.describe(IllegalStateException(secret)).contains(secret))
        try{ArtistLoadingFailure(ArtistFailureReason.REQUEST,MusicError.HTTP,secret);fail("Method must be a fixed name")}
        catch(_: IllegalArgumentException){}
    }
}
