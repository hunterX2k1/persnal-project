OCR donor models: PaddleOCR PP-OCRv3 (Apache License 2.0), ONNX versions as shipped in RapidOCR 1.2.3 (Apache 2.0).
det.onnx = ch_PP-OCRv3_det_infer.onnx (finds text), rec.onnx = ch_PP-OCRv3_rec_infer.onnx (reads text),
keys.txt = the recognizer's character list (from the model's "character" metadata).
Used only for scanned (picture-only) PDF pages. Text found is indexed per sheet; wires are not followed.
