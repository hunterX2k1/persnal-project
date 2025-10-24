import tkinter
import customtkinter
from tkinter import filedialog
from upscaler import VideoUpscaler
import threading

class App(customtkinter.CTk):
    def __init__(self):
        super().__init__()

        self.title("Video Upscaler")
        self.geometry("400x200")

        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=1)

        self.select_button = customtkinter.CTkButton(self, text="Select Video", command=self.select_video)
        self.select_button.grid(row=0, column=0, padx=20, pady=20, sticky="ew")

        self.start_button = customtkinter.CTkButton(self, text="Start Upscaling", command=self.start_upscaling, state="disabled")
        self.start_button.grid(row=1, column=0, padx=20, pady=10, sticky="ew")

        self.progress_bar = customtkinter.CTkProgressBar(self)
        self.progress_bar.set(0)
        self.progress_bar.grid(row=2, column=0, padx=20, pady=20, sticky="ew")

        self.video_path = None
        self.upscaler = VideoUpscaler()

    def select_video(self):
        self.video_path = filedialog.askopenfilename()
        if self.video_path:
            self.start_button.configure(state="normal")

    def start_upscaling(self):
        if self.video_path:
            output_path = self.video_path.replace(".", "_upscaled.")
            self.start_button.configure(state="disabled")
            self.select_button.configure(state="disabled")

            # Run the upscaling in a separate thread to avoid freezing the GUI
            threading.Thread(target=self.upscale_thread, args=(self.video_path, output_path), daemon=True).start()

    def upscale_thread(self, input_path, output_path):
        # This is a placeholder for progress reporting
        # A more advanced implementation would require modifying the upscaler
        self.progress_bar.start()
        self.upscaler.upscale_video(input_path, output_path)
        self.progress_bar.stop()
        self.progress_bar.set(1)
        self.start_button.configure(state="normal")
        self.select_button.configure(state="normal")


if __name__ == "__main__":
    app = App()
    app.mainloop()
