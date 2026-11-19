package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.model.value.Value

/**
 * §12.1's persistent-state hook: where a `Persistence.Saveable` declaration is kept between the
 * composition that wrote it and the next one that reads it.
 *
 * A `Value` in and a `Value` out, because the *encoding* is the host's business and not the
 * runtime's: a host backed by a `Bundle`, a file or a cache picks its own format, and §12.1 calls
 * this a hook rather than a format. The runtime never invents a key either — `SnapshotStateStore`
 * composes one from the scope it was seeded with and the declaration's id, which is what keeps
 * two pages that declare the same id from reading one another's value.
 *
 * Identity and not copying: an entry read twice is answered twice. A store that cannot serve — no
 * file yet, a process that died before its first write — answers null, and the declaration falls
 * back to its `initial` with no error to route.
 */
public interface PersistentStore {

    /** The value [key] was last written with, or null when it was never written. */
    public fun read(key: String): Value?

    /** Records [value] under [key], replacing whatever it held. */
    public fun write(key: String, value: Value)
}
