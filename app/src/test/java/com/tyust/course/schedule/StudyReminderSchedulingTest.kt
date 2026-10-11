package com.tyust.course.schedule

import android.app.Application
import android.content.Context
import com.tyust.course.manager.ScheduleSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 调度器接线：课表快照 → 自习判重。
 * 评审第二轮 P1 就断在这条链上——判重只从提醒记录反推课程，刷新课表新增的课程看不到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class StudyReminderSchedulingTest {
    private val account = "acct-" + UUID.randomUUID()
    private val term = "2026-2027-1"
    private val studyKey = CourseReminderKey(account, term, StudyReminders.courseId(ScheduleStudyKind.Morning, 1))
    private val tuesday = ScheduleCourseRecord("custom:tue", "周二课", "", "", 2, 1, 2, "1-25周")
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = ScheduleReminderScheduler.get(context)

    @Before
    fun setUp() {
        ScheduleSettingsManager.getInstance().apply { init(context) }.saveStudyBlock(
            ScheduleStudyBlock(ScheduleStudyKind.Morning, enabled = true, name = "早自习",
                start = "08:10", end = "09:10", days = setOf(1)), account, term)
        scheduler.updateTimeBase(account, term, ScheduleTimeBase("2026-09-07", mapOf(1 to "08:10"), mapOf(1 to "09:45")))
    }

    @Test
    fun `a new course in the snapshot must block an existing study reminder`() {
        // 只有周二那门课开了提醒，它同时让自习跟着本学期提醒总开关生效。
        scheduler.setEnabled(CourseReminderKey(account, term, tuesday.id), tuesday, true)
        scheduler.updateSnapshot(account, term, listOf(tuesday))
        assertNotNull(scheduler.find(studyKey))
        assertTrue(scheduler.find(studyKey)!!.study!!.blockedWeeks.isEmpty())

        // 刷新课表后新增一门周一 08:00–08:45 的课：它没有提醒记录，但必须压掉第 1–3 周的自习。
        val added = ScheduleCourseRecord("custom:mon", "周一新课", "", "", 1, 1, 1, "1-3周")
        scheduler.updateSnapshot(account, term, listOf(tuesday, added))
        assertEquals(setOf(1, 2, 3), scheduler.find(studyKey)!!.study!!.blockedWeeks)
    }

    @Test
    fun `removing the clashing course frees the study reminder again`() {
        val clash = ScheduleCourseRecord("custom:mon", "周一新课", "", "", 1, 1, 1, "1-25周")
        scheduler.setEnabled(CourseReminderKey(account, term, tuesday.id), tuesday, true)
        scheduler.updateSnapshot(account, term, listOf(tuesday, clash))
        // 每周都被压住 → 不生成自习提醒。
        assertNull(scheduler.find(studyKey))

        scheduler.removeCourse(account, clash.id)
        assertNotNull(scheduler.find(studyKey))
        assertTrue(scheduler.find(studyKey)!!.study!!.blockedWeeks.isEmpty())
    }
}
