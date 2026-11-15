package ai.meeos.tago.study

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** V-12: 전원 차단으로 첫 줄(세션 머리)을 잃은 연구 기록 파일을 알아본다 */
class StudyFileCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file(text: String): File = tmp.newFile().also { it.writeText(text) }

    @Test fun missingOrNullOrEmptyIsMissing() {
        assertTrue(StudyFileCheck.headMissing(null))
        assertTrue(StudyFileCheck.headMissing(File(tmp.root, "none.jsonl")))
        assertTrue(StudyFileCheck.headMissing(file("")))          // 에뮬레이터 전원 차단 시험에서 본 0 바이트 파일
    }
    @Test fun intactHeadIsNotMissing() = assertFalse(StudyFileCheck.headMissing(file("{\"rec\":\"session\",\"session\":\"abc\"}\n{\"rec\":\"session_resume\"}\n")))
    @Test fun truncatedOrWrongFirstLineIsMissing() {
        assertTrue(StudyFileCheck.headMissing(file("{\"rec\":\"sess")))                                   // 잘린 첫 줄
        assertTrue(StudyFileCheck.headMissing(file("{\"rec\":\"session_resume\",\"session\":\"abc\"}\n")))  // 머리 없이 이어 쓰기만 된 파일
        assertTrue(StudyFileCheck.headMissing(file("not json\n")))
    }
}
