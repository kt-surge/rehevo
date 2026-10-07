import unittest

from align_primary_chunks import chunk_parts, codepoint_offset, source_spans


class ChunkAlignmentTest(unittest.TestCase):
    def test_java_offsets_convert_at_complete_surrogate_boundary(self):
        self.assertEqual(codepoint_offset("中😀文", 3), 2)
        with self.assertRaises(UnicodeDecodeError):
            codepoint_offset("中😀文", 2)

    def test_repeated_original_header_can_map_backwards(self):
        parsed = "|h|\n|---|\n一行😀\n二行\n"
        text = "|h|\n|---|\n二行\n"
        start = len(parsed[:parsed.index("二")].encode("utf-16-le")) // 2
        row = {"text": text, "metadata": {"chunking_mode": "STRUCTURED", "source_offset_unit": "UTF16",
               "source_spans": [{"source_start": 0, "source_end": 10, "chunk_start": 0, "chunk_end": 10},
                                {"source_start": start, "source_end": start + 3, "chunk_start": 10, "chunk_end": 13}]}}
        parts, _ = chunk_parts(row, parsed, 0)
        spans = source_spans(parts, [(0, len(parsed), 0, len(parsed))])
        self.assertEqual(len(spans), 2)
        self.assertEqual(spans[1]["sourceStart"], parsed.index("二"))

    def test_nonliteral_or_omitted_actual_chunk_characters_are_rejected(self):
        row = {"text": "abc!", "metadata": {"chunking_mode": "STRUCTURED", "source_offset_unit": "UTF16",
               "source_spans": [{"source_start": 0, "source_end": 3, "chunk_start": 0, "chunk_end": 3}]}}
        with self.assertRaises(ValueError):
            chunk_parts(row, "abc", 0)
        row["text"] = "abd"
        with self.assertRaises(ValueError):
            chunk_parts(row, "abc", 0)

    def test_cleaning_deletion_never_gets_source_credit(self):
        parts = [(0, 4, 0, 4)]
        spans = source_spans(parts, [(0, 2, 0, 2), (3, 5, 2, 4)])
        self.assertEqual([(span["sourceStart"], span["sourceEnd"]) for span in spans], [(0, 2), (3, 5)])

    def test_legacy_requires_ordered_exact_substring(self):
        parts, cursor = chunk_parts({"text": "abc", "chunk_id": "old"}, "abc abc", 3)
        self.assertEqual(parts, [(4, 7, 0, 3)])
        self.assertEqual(cursor, 7)


if __name__ == "__main__":
    unittest.main()
