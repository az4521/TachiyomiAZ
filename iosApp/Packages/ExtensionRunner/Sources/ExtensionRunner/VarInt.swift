import Foundation

public func varInt(_ n: UInt64, data: inout Data) {
    let start = data.count
    var value = n
    for offset in 0..<10 {
        data.append(UInt8(truncatingIfNeeded: value.littleEndian))
        if value < 128 {
            break
        }
        data[start + offset] |= 0x80
        value >>= 7
    }
}

public func decodeVarInt<T: FixedWidthInteger>(
    _ data: Data,
    currentIndex: inout Data.Index
) throws -> T {
    var result: T = 0
    var shift: T = 0
    while currentIndex < data.endIndex {
        let byte = data[currentIndex]
        currentIndex = data.index(after: currentIndex)
        result |= T(byte & 0x7f) << shift
        if byte & 0x80 == 0 {
            return result
        }
        shift += 7
    }
    throw DecodingError.dataCorrupted(.init(
        codingPath: [],
        debugDescription: "Invalid varint encoding"
    ))
}
