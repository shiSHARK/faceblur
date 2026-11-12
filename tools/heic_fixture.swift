// SPDX-License-Identifier: Apache-2.0
// Writes the synthetic HEIC test fixture from the pattern PNG with ImageIO (macOS), and puts CANARY metadata into it:
// EXIF, TIFF, GPS, XMP and an orientation (6 = rotated 90 degrees clockwise when shown). Every canary string starts with
// "FBCANARY"; the device test looks for them in the input and requires that none reaches the output.
// Usage: heic_fixture <in.png> <out.heic>
import Foundation
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write(Data("usage: heic_fixture <in.png> <out.heic>\n".utf8))
    exit(2)
}
guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[1]) as CFURL, nil),
    let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
    let destination = CGImageDestinationCreateWithURL(
        URL(fileURLWithPath: args[2]) as CFURL, UTType.heic.identifier as CFString, 1, nil)
else { exit(3) }

// XMP: two Dublin Core properties.
let xmp = CGImageMetadataCreateMutable()
let creator = CGImageMetadataTagCreate(
    kCGImageMetadataNamespaceDublinCore, kCGImageMetadataPrefixDublinCore, "creator" as CFString,
    .arrayOrdered, ["FBCANARY-XMP-CREATOR"] as CFArray)!
CGImageMetadataSetTagWithPath(xmp, nil, "dc:creator" as CFString, creator)
let rights = CGImageMetadataTagCreate(
    kCGImageMetadataNamespaceDublinCore, kCGImageMetadataPrefixDublinCore, "rights" as CFString,
    .string, "FBCANARY-XMP-RIGHTS" as CFString)!
CGImageMetadataSetTagWithPath(xmp, nil, "dc:rights" as CFString, rights)

let properties: [CFString: Any] = [
    kCGImageDestinationLossyCompressionQuality: 0.9,
    kCGImagePropertyOrientation: 6,
    kCGImagePropertyExifDictionary: [
        kCGImagePropertyExifUserComment: "FBCANARY-EXIF-USERCOMMENT",
        kCGImagePropertyExifDateTimeOriginal: "2001:02:03 04:05:06",
        kCGImagePropertyExifBodySerialNumber: "FBCANARY-EXIF-SERIAL",
    ],
    kCGImagePropertyTIFFDictionary: [
        kCGImagePropertyTIFFMake: "FBCANARY-MAKE",
        kCGImagePropertyTIFFModel: "FBCANARY-MODEL",
        kCGImagePropertyTIFFArtist: "FBCANARY-ARTIST",
        kCGImagePropertyTIFFImageDescription: "FBCANARY-TIFF-DESC",
    ],
    kCGImagePropertyGPSDictionary: [
        kCGImagePropertyGPSLatitude: 12.3456,
        kCGImagePropertyGPSLatitudeRef: "N",
        kCGImagePropertyGPSLongitude: 65.4321,
        kCGImagePropertyGPSLongitudeRef: "E",
    ],
]
CGImageDestinationAddImageAndMetadata(destination, image, xmp, properties as CFDictionary)
guard CGImageDestinationFinalize(destination) else { exit(5) }
