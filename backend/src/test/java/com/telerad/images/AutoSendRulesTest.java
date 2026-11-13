package com.telerad.images;

import com.telerad.common.StudyPriority;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoSendRulesTest {
    private static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");

    private static ZonedDateTime at(int hour) {
        return ZonedDateTime.of(2026, 11, 13, hour, 30, 0, 0, ADDIS);
    }

    @Test
    void nightCtRuleWrapsPastMidnight() {
        var rules = AutoSendRules.parse("[{\"modality\":\"CT\",\"fromHour\":18,\"toHour\":8}]");
        assertThat(AutoSendRules.matches(rules, "CT", StudyPriority.ROUTINE, at(22))).isTrue();
        assertThat(AutoSendRules.matches(rules, "CT", StudyPriority.ROUTINE, at(3))).isTrue();
        assertThat(AutoSendRules.matches(rules, "CT", StudyPriority.ROUTINE, at(8))).isFalse();
        assertThat(AutoSendRules.matches(rules, "CT", StudyPriority.ROUTINE, at(12))).isFalse();
        assertThat(AutoSendRules.matches(rules, "MR", StudyPriority.ROUTINE, at(22))).isFalse();
    }

    @Test
    void anyRuleMayMatch() {
        var rules = AutoSendRules.parse("[{\"modality\":\"CT\",\"fromHour\":18,\"toHour\":8},{\"priority\":\"STAT\"}]");
        assertThat(AutoSendRules.matches(rules, "MR", StudyPriority.STAT, at(12))).isTrue();
        assertThat(AutoSendRules.matches(rules, "MR", StudyPriority.ROUTINE, at(12))).isFalse();
        assertThat(AutoSendRules.matches(List.of(), "CT", StudyPriority.STAT, at(12))).isFalse();
    }

    @Test
    void validatesWhatAdminsType() {
        assertThat(AutoSendRules.normalise("  ")).isNull();
        assertThat(AutoSendRules.normalise("[]")).isNull();
        assertThat(AutoSendRules.normalise("[{\"modality\":\"CT\"}]")).isEqualTo("[{\"modality\":\"CT\"}]");
        assertThatThrownBy(() -> AutoSendRules.normalise("[{\"fromHour\":18}]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoSendRules.normalise("[{\"fromHour\":30,\"toHour\":2}]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoSendRules.normalise("[{\"priority\":\"SOON\"}]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoSendRules.normalise("not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
