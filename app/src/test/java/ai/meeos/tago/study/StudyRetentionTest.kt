package ai.meeos.tago.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 연구 기록 보존 기한: 오래된 세션 파일만 지우고, 지금 쓰는 파일·최근 파일·다른 종류 파일은 둔다 */
class StudyRetentionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun file(name: String, ageDays: Double): File =
        tmp.newFile(name).also { it.writeText("x"); it.setLastModified(now - (ageDays * day).toLong()) }

    @Test fun deletesOnlyOldSessionFiles() {
        val old = file("aaaaaaaaaaaa.jsonl", 31.0)
        val fresh = file("bbbbbbbbbbbb.jsonl", 29.0)
        val other = file("note.txt", 400.0)
        assertEquals(1, StudyRetention.purge(tmp.root, now))
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(other.exists())
    }

    @Test fun keepsCurrentSessionFileEvenIfOld() {
        val cur = file("cccccccccccc.jsonl", 90.0)
        assertEquals(0, StudyRetention.purge(tmp.root, now, keep = cur))
        assertTrue(cur.exists())
    }

    @Test fun missingDirIsNoOp() = assertEquals(0, StudyRetention.purge(File(tmp.root, "nope"), now))

    // 시계 가드: 헤드유닛 시계가 틀렸다가 맞춰진 경우(전원 차단 뒤 1970 년 → 현재)에 participant 파일이 통째로 지워지지 않는다
    @Test fun keepsFilesWrittenUnderWrongClock() {
        val wrong = file("dddddddddddd.jsonl", 0.0).also { it.setLastModified(946_684_800_000L) }   // 2000-01-01
        val epoch0 = file("eeeeeeeeeeee.jsonl", 0.0).also { it.setLastModified(1000L) }              // 1970 년
        val old = file("ffffffffffff.jsonl", 40.0)                                                    // 시계가 맞을 때 쓴 진짜 오래된 파일
        assertEquals(1, StudyRetention.purge(tmp.root, now))
        assertTrue(wrong.exists()); assertTrue(epoch0.exists()); assertFalse(old.exists())
    }

    @Test fun deletesNothingWhileClockIsStillWrong() {
        val f = file("gggggggggggg.jsonl", 0.0).also { it.setLastModified(1_790_000_000_000L) }   // 2026-09, 앱 출시 전 시각
        assertEquals(0, StudyRetention.purge(tmp.root, 946_684_800_000L + 90 * day))   // 지금이 2000 년
        assertTrue(f.exists())
    }
}
