package com.cnumed.mlms.widget

/** 학기 마지막 날(종강)까지 D-day를 보여주는 위젯. */
class SemesterEndWidget : CountdownWidget() {
    override val mode = Mode.SEMESTER_END
}
