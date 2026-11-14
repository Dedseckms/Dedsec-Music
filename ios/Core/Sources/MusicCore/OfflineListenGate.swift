import Foundation

public struct OfflineListenGate {
    private var key = ""
    private var position: Double = 0
    private var time: Double = 0
    private var playing = false
    private var listened: Double = 0
    private var offered = false
    public init() {}
    public mutating func observe(key: String, playing: Bool, position: Double, duration: Double, clock: Double) -> Bool {
        let elapsed = clock - time, advance = position - self.position
        if key != self.key { self.key = key; listened = 0; offered = false }
        else if self.playing && playing && elapsed > 0 && elapsed <= 5 && advance > 0 && advance <= elapsed + 1.5 {
            listened += min(elapsed, advance)
        }
        self.position = position; time = clock; self.playing = playing
        if !offered && duration > 0 && listened >= duration * 0.8 { offered = true; return true }
        return false
    }
}
