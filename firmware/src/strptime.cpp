#include <time.h>
#include <ctype.h>
#include <string.h>
#include <strings.h>
#include <stdlib.h>

// C++ linkage (no extern "C") to match what HTTPClient.cpp expects
// (lt_posix_api.h declares strptime without extern "C")

char *strptime(const char * s, const char * f, struct tm * tm) {
    int i, w, neg, adj, min, range, *dest, dummy, century = 0;
    int want_century = 0;
    const char *ex;
    size_t len;

    while (*f) {
        if (*f != '%') {
            if (isspace(*f)) { while (*s && isspace(*s)) s++; }
            else if (*s != *f) return 0;
            else s++;
            f++;
            continue;
        }
        f++;
        if (*f == '+') f++;
        if (isdigit(*f)) { w = 0; while (isdigit(*f)) { w = w * 10 + (*f - '0'); f++; } }
        else w = -1;
        adj = 0;
        switch (*f++) {
        case 'a': case 'A':
            dest = &tm->tm_wday; min = 1; range = 7;
            goto symbolic_range;
        case 'b': case 'B': case 'h':
            dest = &tm->tm_mon; min = 1; range = 12;
            goto symbolic_range;
        case 'd': case 'e':
            dest = &tm->tm_mday; min = 1; range = 31;
            goto numeric_range;
        case 'H':
            dest = &tm->tm_hour; min = 0; range = 24;
            goto numeric_range;
        case 'I':
            dest = &tm->tm_hour; min = 1; range = 12;
            goto numeric_range;
        case 'j':
            dest = &tm->tm_yday; min = 1; range = 366;
            goto numeric_range;
        case 'm':
            dest = &tm->tm_mon; min = 1; range = 12; adj = -1;
            goto numeric_range;
        case 'M':
            dest = &tm->tm_min; min = 0; range = 60;
            goto numeric_range;
        case 'S':
            dest = &tm->tm_sec; min = 0; range = 61;
            goto numeric_range;
        case 'U': case 'W':
            dest = &dummy; min = 0; range = 54;
            goto numeric_range;
        case 'w':
            dest = &tm->tm_wday; min = 0; range = 7;
            goto numeric_range;
        case 'y':
            dest = &tm->tm_year; w = 2; want_century |= 1;
            goto numeric_digits;
        case 'Y':
            dest = &tm->tm_year; if (w < 0) w = 4; adj = -1900; want_century = 0;
            goto numeric_digits;
        case 'C':
            dest = &century; if (w < 0) w = 2; want_century |= 2;
            goto numeric_digits;
        case 'n': case 't':
            while (*s && isspace(*s)) s++;
            break;
        case '%':
            if (*s++ != '%') return 0;
            break;
        default:
            return 0;
        numeric_range:
            if (!isdigit(*s)) return 0;
            *dest = 0;
            for (i = 1; i <= min + range && isdigit(*s); i *= 10)
                *dest = *dest * 10 + *s++ - '0';
            if (*dest - min >= (unsigned)range) return 0;
            *dest += adj;
            break;
        numeric_digits:
            neg = 0;
            if (*s == '+') s++;
            else if (*s == '-') { neg = 1; s++; }
            if (!isdigit(*s)) return 0;
            for (*dest = 0, i = 0; i < w && isdigit(*s); i++)
                *dest = *dest * 10 + *s++ - '0';
            if (neg) *dest = -*dest;
            *dest += adj;
            break;
        symbolic_range:
            for (i = 2 * range - 1; i >= 0; i--) {
                static const char* day_names[] = {
                    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
                    "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
                };
                static const char* mon_names[] = {
                    "January", "February", "March", "April", "May", "June",
                    "July", "August", "September", "October", "November", "December",
                    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
                };
                if (range == 7) {
                    ex = day_names[i];
                } else {
                    ex = mon_names[i];
                }
                len = strlen(ex);
                if (strncasecmp(s, ex, len)) continue;
                s += len;
                *dest = i % range;
                break;
            }
            if (i < 0) return 0;
            break;
        }
    }
    if (want_century) {
        if (want_century & 2) tm->tm_year += century * 100 - 1900;
        else if (tm->tm_year <= 68) tm->tm_year += 100;
    }
    return (char *)s;
}
