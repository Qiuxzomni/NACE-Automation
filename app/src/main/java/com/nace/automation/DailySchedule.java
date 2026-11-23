package com.nace.automation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.BitSet;
import java.util.Locale;
import java.util.Objects;

final class DailySchedule {
    static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
    static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(2);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final int MINUTES_PER_DAY = 24 * 60;

    private DailySchedule() {}

    static LocalTime time(String value) {
        if (value == null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw new IllegalArgumentException("Use a 24-hour time in HH:mm format.");
        }
        return LocalTime.parse(value);
    }

    static void addInterval(BitSet occupied, LocalTime start, LocalTime end) {
        int startMinute = start.toSecondOfDay() / 60;
        int endMinute = end.toSecondOfDay() / 60;
        if (startMinute == endMinute) {
            throw new IllegalArgumentException("An interval needs different start and end times.");
        }
        for (int minute = startMinute; minute != endMinute; minute = (minute + 1) % MINUTES_PER_DAY) {
            if (occupied.get(minute)) {
                throw new IllegalArgumentException("Scheduled intervals cannot overlap.");
            }
            occupied.set(minute);
        }
    }

    static Occurrence occurrence(LocalDate date, LocalTime start, LocalTime end) {
        return occurrence(date, start, end, ZONE);
    }

    static Occurrence occurrence(LocalDate date, LocalTime start, LocalTime end, ZoneId zone) {
        LocalDate endDate = end.isAfter(start) ? date : date.plusDays(1);
        return new Occurrence(
                date,
                start,
                end,
                ZonedDateTime.of(date, start, zone),
                ZonedDateTime.of(endDate, end, zone).withLaterOffsetAtOverlap());
    }

    static Occurrence forDisplayDay(ZonedDateTime localNow, LocalTime start, LocalTime end, ZoneId scheduleZone) {
        LocalDate scheduleDate = localNow.withZoneSameInstant(scheduleZone).toLocalDate();
        for (int day = -1; day <= 2; day++) {
            Occurrence candidate = occurrence(scheduleDate.plusDays(day), start, end, scheduleZone);
            LocalDate localDate = candidate.from.withZoneSameInstant(localNow.getZone()).toLocalDate();
            if (!localDate.isBefore(localNow.toLocalDate())) return candidate;
        }
        throw new IllegalStateException("Could not determine the next scheduled date.");
    }

    static final class Occurrence {
        final LocalDate date;
        final LocalTime start;
        final LocalTime end;
        final ZonedDateTime from;
        final ZonedDateTime until;

        Occurrence(LocalDate date, LocalTime start, LocalTime end, ZonedDateTime from, ZonedDateTime until) {
            this.date = Objects.requireNonNull(date);
            this.start = start;
            this.end = end;
            this.from = from;
            this.until = until;
        }

        boolean contains(ZonedDateTime time) {
            return !time.isBefore(from) && time.isBefore(until);
        }

        boolean overlaps(Occurrence other) {
            return from.isBefore(other.until) && other.from.isBefore(until);
        }

        String interval(ZoneId displayZone, String separator) {
            return from.withZoneSameInstant(displayZone).format(CLOCK) + separator
                    + until.withZoneSameInstant(displayZone).format(CLOCK);
        }

        String displayLabel(ZoneId displayZone) {
            return from.withZoneSameInstant(displayZone).toLocalDate() + " · " + interval(displayZone, "–");
        }
    }
}
