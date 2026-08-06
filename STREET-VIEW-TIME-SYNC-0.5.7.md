# Labpano GPX Extractor 0.5.7 — Street View time synchronization

The previous parser assigned CAMM type 5 GPS points from the CAMM track's `mdhd` creation time and did not apply the track's MP4 edit list. Street View Studio compares the GPX range against the presented MP4 video range. When the CAMM track and movie/video timeline have different creation times or a leading edit, the two ranges can touch without overlapping or can be separated completely.

Version 0.5.7 resolves the absolute GPX timeline in this order:

1. Primary video-track (`mdhd`) creation time.
2. MP4 movie-header (`mvhd`) creation time.
3. Recording filename timestamp (`yyMMdd_HHmmssSSS`).
4. CAMM-track creation time.
5. File modification time minus movie duration.

CAMM sample PTS values are mapped through `edts/elst` onto the movie presentation timeline. For CAMM type 6 packets, a stable constant difference between GPS epoch time and MP4 presentation time is corrected without changing the spacing between samples. A no-overlap safety correction is applied only as a constant shift, so coordinates, ordering and gap durations are preserved.

Reports now include:

- `timestampAnchor`
- `timestampShiftMs`
- `videoStartUtc`
- `videoEndUtc`
- `gpxStartUtc`
- `gpxEndUtc`
- `overlapMs`

A positive `overlapMs` confirms that the generated GPX time range overlaps the MP4 time range as interpreted by the parser.
