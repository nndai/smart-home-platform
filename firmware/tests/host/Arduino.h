#pragma once
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#include <algorithm>
#include <ctime>
#ifdef _WIN32
inline tm* gmtime_r(const time_t* value, tm* result) { *result = *std::gmtime(value); return result; }
#endif
class __FlashStringHelper {};
class String : public std::string {
public:
    using std::string::string;
    String(const std::string& s) : std::string(s) {}
    bool endsWith(const char* s) const { size_t n = strlen(s); return size() >= n && compare(size()-n,n,s)==0; }
    int lastIndexOf(char c) const { auto n = rfind(c); return n == npos ? -1 : int(n); }
    String substring(size_t n) const { return substr(n); }
};
inline uint32_t hostMillis = 0;
inline uint32_t millis() { return hostMillis; }
