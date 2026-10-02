# Porting LurkerKit to Kotlin

`:lurkerkit` is a file-for-file Kotlin port of the Swift package at
[`lurker-ios/LurkerKit`](https://github.com/amiantos/lurker-ios/tree/main/LurkerKit). The Swift
is the reference; this document is the set of rulings that keep every ported file consistent
with every other, and [`LEDGER.md`](LEDGER.md) records what has been ported and from which
commit.

It is a translation, not a redesign. The Swift carries a year of hard-won behaviour in its
comments and its tests, and the point of porting file for file is that all of it arrives
intact and stays diffable: when LurkerKit changes, `git log <pin>..main -- <file>.swift` says
which Kotlin file to revisit.

## Layout

| Swift | Kotlin |
|---|---|
| `Sources/LurkerKit/<Dir>/<Name>.swift` | `src/main/kotlin/net/amiantos/lurkerkit/<dir>/<Name>.kt`, package `net.amiantos.lurkerkit.<dir>` |
| `Tests/LurkerKitTests/<Name>Tests.swift` | `src/test/kotlin/net/amiantos/lurkerkit/<Name>Tests.kt`, package `net.amiantos.lurkerkit` |

- One Kotlin file per Swift file, same name. (`JSON.swift` → `Json.kt` is the one exception.)
- Type, function and property names are kept, so a name greps across both repos.
- Test classes keep their name; test methods keep their name **and order**
  (`func testFoo()` → `@Test fun testFoo()`), so the two suites can be read side by side.
- `support/` holds what exists only on this side (`TextRange`, `unicodeRegex`). Nothing in it
  has a Swift counterpart.
- Every file starts with the MPL header the rest of the repo uses.

## The module boundary

`:lurkerkit` is a plain JVM module. It must never depend on Android. What needs the platform
is an interface here and an implementation in `:app`:

| LurkerKit uses | Here |
|---|---|
| `URLSession`, `URLRequest` | OkHttp, inside the kit (it is pure JVM) |
| Keychain (`SessionStore`) | interface in the kit, Keystore implementation in `:app` |
| `UserDefaults` (`SettingsCache`, `OAuthClients`) | interface in the kit, DataStore/SharedPreferences in `:app` |
| `CryptoKit` | `java.security` |
| `UniformTypeIdentifiers` | MIME type strings |
| Combine (`CurrentValueSubject`, `PassthroughSubject`) | `StateFlow`, `SharedFlow` |
| `@MainActor` | main-thread confinement, stated in the KDoc |

## Types

| Swift | Kotlin |
|---|---|
| `struct` | `data class`, `val` properties only |
| `enum` with no payloads | `enum class`, cases in `PascalCase` |
| `enum Foo: String` | `enum class Foo(val rawValue: String)` + `companion fun fromRawValue(raw: String): Foo?` |
| `enum` with associated values | `sealed interface`, cases as `data class` / `data object` |
| caseless `enum` used as a namespace | `object` |
| `protocol` | `interface` |
| `static let` / `static func` | `companion object` member (or `object` member) |
| computed property | `val x: T get() = …` |
| `T?` | `T?` |
| `Int` | `Int` — **except** message/event ids, byte counts and epoch milliseconds, which are `Long` |
| `Date` | `java.time.Instant` |
| `TimeInterval` | `java.time.Duration` |
| `Data` | `okio.ByteString` in a stored property (value equality); `ByteArray` only in passing |
| `URL` | `okhttp3.HttpUrl` for http(s); `String` where it is only carried. Never `java.net.URL` |
| `NSRange` | `support.TextRange` |
| `[T]`, `[K: V]`, `Set<T>` | `List<T>`, `Map<K, V>`, `Set<T>` (the read-only interfaces) |
| `CaseIterable.allCases` | `entries` |
| `Sendable`, `nonisolated`, `@unchecked` | dropped |
| `public` / (default) / `private`, `fileprivate` | public / `internal` / `private` |

**Argument labels.** A Kotlin parameter takes the Swift *label* when the label is a noun
(`paletteCount:`), and the Swift *internal name* when the label is `_` or a bare preposition
(`string(from date: Date)` → `string(date: Instant)`).

**`switch` → `when`.** Exhaustive over an enum or sealed type, with no `else` branch, so that
adding a case breaks the build here exactly as it does in Swift.

**Thrown errors** (`enum FooError: Error`) → `sealed class FooError : Exception()` with
`data object` / `data class` cases, so they still compare equal in tests. An error enum that is
only ever carried as a value stays a plain `enum class` / `sealed interface`.

**Named tuples** in a signature become a small `data class`.

### Structs that mutate

Swift copies a struct on every assignment; Kotlin shares a reference. Anything that ends up
inside the published `ChatState` must be immutable, or a mutation in place will neither
publish nor compare as a change.

1. No `mutating` methods → immutable `data class`.
2. `mutating func` returning `Void` → immutable `data class`; the method keeps its name and
   returns the updated copy (`progress = progress.apply(frame)`).
3. `mutating func` that also returns a value (a small state machine: `OutgoingTyping`) →
   a plain `class` with `private set` properties and the same signatures. It has one owner,
   it never goes into `ChatState`, and it never travels through a flow. **Note every one of
   these in the ledger.**

## Strings

This is where a faithful-looking translation goes wrong.

- A Swift `String` is a sequence of grapheme clusters; a Kotlin `String` is UTF-16 code units,
  the same as `NSString`. So `NSRange`, `(text as NSString)` and `.utf16` offsets carry over
  **unchanged**. `text.count`, `text.first`, `dropFirst()` and `Character` do not: decide what
  the Swift was counting. Where it matters (emoji in a reaction, a user-visible length limit),
  step by code point or grapheme and say so in a `Port note:`.
- `Unicode.Scalar` iteration → code points (`codePointAt` / `Character.charCount`).
- `lowercased()` / `uppercased()` → `lowercase()` / `uppercase()`. Never a `Locale`-taking
  overload with the default locale. Where the Swift folds ASCII only (IRC targets), so does
  the Kotlin.
- `trimmingCharacters(in: .whitespacesAndNewlines)` → `trim()`.
  `.whitespaces` (no newlines) → `trim { it.isWhitespace() && it != '\n' && it != '\r' }`.
- ⚠ `split(separator:)` **omits empty pieces** by default; Kotlin's `split` keeps them. Port it
  as `split(…).filter { it.isNotEmpty() }` unless the Swift passes
  `omittingEmptySubsequences: false`. `components(separatedBy:)` keeps them, like Kotlin.
- `String(format:)` → `String.format(Locale.ROOT, …)`.
- `localizedStandardCompare` / `localizedCaseInsensitiveCompare` need `java.text.Collator`;
  note it in the ledger rather than substituting `compareTo`.
- **Regular expressions go through `support.unicodeRegex`**, never a bare `Regex(…)`. The
  host JVM, the Android device and iOS run three different engines; that function's comment
  is the explanation.

## JSON

- A `[String: Any]` from `JSONSerialization` → `kotlinx.serialization.json.JsonObject`, read
  through the helpers in `client/Json.kt` (`stringOrNull`, `string`, `intOrNull`, `int`,
  `longOrNull`, `long`, `bool`, `objects`, `has`). They are type-strict the way `as?` is.
  ⚠ Never read `JsonPrimitive.content` or `.int` directly: both coerce (`"3"` → 3, `3` → "3").
- `Codable` → `@Serializable`.
- A test that builds a dictionary literal → `Json.parseToJsonElement("""…""")` or
  `buildJsonObject { … }`.

## Comments

The comments are the lore, and they are ported with the code.

- Port every doc comment (as KDoc) and every inline comment, including the `⚠` marks.
- Reword only what is no longer true of the Kotlin: Apple API names, Swift language mechanics.
  Where a comment recounts a bug that happened on iOS, keep it and say "on iOS".
- A bare `#123` in the Swift is a lurker-ios issue: write `lurker-ios#123`. `lurker#123` stays.
- Do not invent warnings. Where the Kotlin deliberately differs from the Swift, say so in a
  comment that starts `Port note:`.

## Tests

`kotlin.test` assertions on JUnit 4.

| XCTest | kotlin.test |
|---|---|
| `XCTAssertEqual(actual, expected, "msg")` | `assertEquals(expected, actual, "msg")` — expected first |
| `XCTAssertTrue(x, "msg")` / `XCTAssertFalse` | `assertTrue(x, "msg")` / `assertFalse` |
| `XCTAssertNil(x)` / `XCTAssertNotNil(x)` | `assertNull(x)` / `assertNotNull(x)` |
| `try XCTUnwrap(x)` | `assertNotNull(x)` (it returns the non-null value) |
| `XCTAssertThrowsError` | `assertFailsWith<…>` |

- **Never weaken a test to make it pass.** If the Kotlin disagrees with a Swift expectation,
  the Kotlin is wrong. If the expectation is meaningless on this platform, leave the test out
  and record it in the ledger with the reason.
- No test is dropped silently. One that needs a type not ported yet is listed in the ledger
  and comes back with that type.
- Tests this port adds (usually to pin something Swift gets for free) go at the bottom of the
  class under a `// Port-only:` comment.
- ⚠ The host tests run OpenJDK. Regex and `java.time` behaviour on the device is the same API
  over a different implementation; a file that leans on either is a candidate for the
  instrumented suite when one exists.

## What not to do

- **Do not stub.** If a file needs a type that is not ported yet, the file waits. Port in the
  ledger's order.
- **Do not improve.** No renames, no restructuring, no "more idiomatic" rewrite of logic. An
  improvement worth making is made in LurkerKit and ported.
- **Do not port from a feature branch.** The ledger's pin is a commit on lurker-ios `main`.

## Running

```bash
./gradlew :lurkerkit:test
```

Without Android Studio's JDK on the path:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :lurkerkit:test`
