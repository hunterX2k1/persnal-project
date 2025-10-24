import cv2
import os

class VideoUpscaler:
    def __init__(self, model_path="models/FSRCNN_x3.pb"):
        self.model_name = "fsrcnn"
        self.scale = 3
        self.sr = cv2.dnn_superres.DnnSuperResImpl_create()
        self.sr.readModel(model_path)
        self.sr.setModel(self.model_name, self.scale)

    def upscale_video(self, input_path, output_path):
        cap = cv2.VideoCapture(input_path)
        if not cap.isOpened():
            print("Error opening video stream or file")
            return

        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        fps = cap.get(cv2.CAP_PROP_FPS)
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) * self.scale)
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) * self.scale)
        out = cv2.VideoWriter(output_path, fourcc, fps, (width, height))

        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break

            upscaled_frame = self.sr.upsample(frame)
            out.write(upscaled_frame)

        cap.release()
        out.release()
        print(f"Video upscaled and saved to {output_path}")
