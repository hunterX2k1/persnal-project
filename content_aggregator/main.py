import kivy
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.textinput import TextInput
from kivy.uix.button import Button
from kivy.uix.image import Image, AsyncImage
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.gridlayout import GridLayout
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.clock import Clock
import requests
import os
import threading
kivy.require('2.1.0')

class SearchScreen(Screen):
    def __init__(self, **kwargs):
        super(SearchScreen, self).__init__(**kwargs)
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)

        layout.add_widget(Label(text='Reverse Face Search', font_size='24sp', size_hint_y=None, height=50))
        self.image_preview = Image(source='', size_hint_y=None, height=300, allow_stretch=True)
        layout.add_widget(self.image_preview)
        self.url_input = TextInput(hint_text='Enter image URL here', multiline=False, size_hint_y=None, height=40)
        self.url_input.bind(on_text_validate=self.load_image_from_url)
        layout.add_widget(self.url_input)

        button_layout = BoxLayout(size_hint_y=None, height=50, spacing=10)
        load_url_button = Button(text='Load from URL')
        load_url_button.bind(on_press=self.load_image_from_url)
        button_layout.add_widget(load_url_button)
        load_file_button = Button(text='Load from File')
        load_file_button.bind(on_press=self.open_file_chooser)
        button_layout.add_widget(load_file_button)
        layout.add_widget(button_layout)

        search_button = Button(text='Search', background_color=(0.2, 0.6, 1, 1), size_hint_y=None, height=50)
        search_button.bind(on_press=self.start_search)
        layout.add_widget(search_button)

        self.status_label = Label(text='Please enter an image URL or load a file to begin.', size_hint_y=None, height=30, markup=True)
        layout.add_widget(self.status_label)

        # Live Log Area
        layout.add_widget(Label(text="Live Log", size_hint_y=None, height=20, font_size='16sp'))
        self.log_box = TextInput(text="", readonly=True, background_color=(0.1, 0.1, 0.1, 1), foreground_color=(0.8, 0.8, 0.8, 1))
        layout.add_widget(self.log_box)

        self.add_widget(layout)

    def load_image_from_url(self, instance):
        url = self.url_input.text
        if not url:
            self.status_label.text = "[color=ff0000]URL cannot be empty.[/color]"
            return
        self.status_label.text = f"Downloading image..."
        try:
            headers = {'User-Agent': 'Mozilla/5.0'}
            response = requests.get(url, headers=headers, stream=True, timeout=10)
            response.raise_for_status()
            if not os.path.exists("temp"):
                os.makedirs("temp")
            temp_image_path = "temp/preview_image.jpg"
            with open(temp_image_path, 'wb') as f:
                f.write(response.content)
            self.image_preview.source = temp_image_path
            self.image_preview.reload()
            self.status_label.text = "[color=00ff00]Image loaded successfully.[/color]"
        except Exception as e:
            self.status_label.text = f"[color=ff0000]Error: Could not load image.[/color]\n{e}"
            self.image_preview.source = ''

    def open_file_chooser(self, instance):
        from plyer import filechooser
        try:
            file_paths = filechooser.open_file(
                title="Pick an Image",
                filters=[("Image files", "*.jpg;*.jpeg;*.png")]
            )
            if file_paths:
                self.load_image_from_file(file_paths[0])
        except Exception as e:
            self.status_label.text = f"[color=ff0000]Error: Could not open file dialog: {e}[/color]"

    def load_image_from_file(self, filepath):
        if not filepath:
            return
        self.image_preview.source = filepath
        self.image_preview.reload()
        self.status_label.text = "[color=00ff00]Image loaded successfully.[/color]"

    def start_search(self, instance):
        if not self.image_preview.source or not os.path.exists(self.image_preview.source):
            self.status_label.text = "[color=ff0000]Please load a valid image before searching.[/color]"
            return

        self.log_box.text = "" # Clear the log box
        self.status_label.text = "[color=ffff00]Search starting... This can take several minutes.[/color]"
        threading.Thread(target=self.run_search_thread).start()

    def run_search_thread(self):
        from scraping.web_scraper import WebScraper
        from vision.face_analyzer import FaceAnalyzer

        # Create a logger function that safely updates the UI from the thread
        def log_to_gui(message):
            Clock.schedule_once(lambda dt: self.update_log_box(message))

        log_to_gui("Search process started.")
        image_path = os.path.abspath(self.image_preview.source)

        log_to_gui("Step 1/3: Searching for potential images...")
        scraper = WebScraper(logger=log_to_gui)
        result_urls = scraper.search_by_image(image_path)

        if not result_urls:
            log_to_gui("Search complete. No potential images found.")
            Clock.schedule_once(lambda dt: setattr(self.status_label, 'text', "Search complete."))
            return

        log_to_gui(f"Step 2/3: Found {len(result_urls)} potential images. Analyzing faces...")
        analyzer = FaceAnalyzer(logger=log_to_gui)
        matching_urls = analyzer.find_matching_faces(image_path, result_urls)

        final_message = f"Step 3/3: Analysis complete. Found {len(matching_urls)} matches."
        log_to_gui(final_message)
        Clock.schedule_once(lambda dt: setattr(self.status_label, 'text', final_message))

        Clock.schedule_once(lambda dt: self.show_results(matching_urls))

    def update_log_box(self, message):
        self.log_box.text += f"{message}\n"

    def show_results(self, matching_urls):
        self.manager.current = 'results'
        self.manager.get_screen('results').display_matches(matching_urls)

class ResultsScreen(Screen):
    def __init__(self, **kwargs):
        super(ResultsScreen, self).__init__(**kwargs)
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)

        # Header
        header = BoxLayout(size_hint_y=None, height=50)
        self.results_label = Label(text="Found 0 Matches", font_size='20sp')
        header.add_widget(self.results_label)
        back_button = Button(text="< Back to Search", size_hint_x=None, width=150)
        back_button.bind(on_press=self.go_back)
        header.add_widget(back_button)
        layout.add_widget(header)

        # Scrollable grid for images
        scroll_view = ScrollView()
        self.grid = GridLayout(cols=3, spacing=10, size_hint_y=None)
        self.grid.bind(minimum_height=self.grid.setter('height'))
        scroll_view.add_widget(self.grid)
        layout.add_widget(scroll_view)

        self.add_widget(layout)

    def display_matches(self, urls):
        self.grid.clear_widgets()
        self.results_label.text = f"Found {len(urls)} Matches"
        if not urls:
            self.grid.add_widget(Label(text="No matching faces were found."))
            return
        for url in urls:
            image = AsyncImage(source=url, size_hint_y=None, height=200, allow_stretch=True)
            self.grid.add_widget(image)

    def go_back(self, instance):
        self.manager.current = 'search'

class FaceSearchApp(App):
    def build(self):
        sm = ScreenManager()
        sm.add_widget(SearchScreen(name='search'))
        sm.add_widget(ResultsScreen(name='results'))
        return sm

if __name__ == '__main__':
    FaceSearchApp().run()