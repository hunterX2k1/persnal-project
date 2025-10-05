from kivy.app import App
from kivy.lang import Builder
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.uix.filechooser import FileChooserIconView
import os
from search import reverse_image_search, scrape_page_summary, analyze_text_for_details
from kivy.uix.scrollview import ScrollView
from kivy.uix.gridlayout import GridLayout
from kivy.core.window import Window
from kivy.uix.tabbedpanel import TabbedPanel, TabbedPanelItem
import webbrowser
import threading
from kivy.clock import Clock
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.uix.checkbox import CheckBox

# Define a custom template for the file chooser entries to show thumbnails
Builder.load_string('''
<ThumbnailFileChooserEntry@BoxLayout>:
    orientation: 'vertical'
    padding: 5
    path: ctx.path
    filename: ctx.name
    isdir: ctx.isdir
    controller: ctx.controller
    canvas.before:
        Color:
            rgba: (.6, .6, .6, .3) if self.selected else (0, 0, 0, 0)
        Rectangle:
            pos: self.pos
            size: self.size
    Image:
        source: 'atlas://data/images/defaulttheme/filechooser_folder' if ctx.isdir else ctx.path
        keep_ratio: True
        allow_stretch: True
    Label:
        text: ctx.name
        size_hint_y: None
        height: '20dp'
        halign: 'center'
        shorten: True
        shorten_from: 'middle'
''')

class MainScreen(Screen):
    def __init__(self, **kwargs):
        super(MainScreen, self).__init__(**kwargs)
        self.selected_filepath = None
        self.main_layout = BoxLayout(orientation='vertical', padding=10, spacing=10)

        # 1. Image Input Area
        input_layout = BoxLayout(orientation='horizontal', size_hint_y=None, height=80)
        url_layout = BoxLayout(orientation='vertical')
        url_layout.add_widget(Label(text="Image URL", size_hint_y=None, height=20))
        self.url_input = TextInput(hint_text='Enter image URL here', multiline=False)
        url_layout.add_widget(self.url_input)
        upload_layout = BoxLayout(orientation='vertical')
        upload_button = Button(text='Upload Image', size_hint_x=None, width=150)
        upload_button.bind(on_press=self.show_file_chooser)
        self.upload_label = Label(text="No file selected", size_hint_y=None, height=20)
        upload_layout.add_widget(upload_button)
        upload_layout.add_widget(self.upload_label)
        input_layout.add_widget(url_layout)
        input_layout.add_widget(upload_layout)
        self.main_layout.add_widget(input_layout)

        # 2. Results Display Area
        self.results_panel = TabbedPanel(do_default_tab=False, tab_pos='top_mid', size_hint=(1, 0.8))
        self.links_tab = TabbedPanelItem(text='Links')
        self.results_scrollview = ScrollView()
        self.results_layout = GridLayout(cols=1, spacing=10, size_hint_y=None)
        self.results_layout.bind(minimum_height=self.results_layout.setter('height'))
        self.results_scrollview.add_widget(self.results_layout)
        self.links_tab.add_widget(self.results_scrollview)
        self.results_panel.add_widget(self.links_tab)
        self.summary_tab = TabbedPanelItem(text='Summary')
        self.summary_scroll = ScrollView()
        self.summary_layout = GridLayout(cols=1, spacing=10, size_hint_y=None)
        self.summary_layout.bind(minimum_height=self.summary_layout.setter('height'))
        self.summary_scroll.add_widget(self.summary_layout)
        self.summary_tab.add_widget(self.summary_scroll)
        self.results_panel.add_widget(self.summary_tab)
        self.analysis_tab = TabbedPanelItem(text='Analysis')
        self.analysis_scroll = ScrollView()
        self.analysis_layout = GridLayout(cols=1, spacing=10, size_hint_y=None)
        self.analysis_layout.bind(minimum_height=self.analysis_layout.setter('height'))
        self.analysis_scroll.add_widget(self.analysis_layout)
        self.analysis_tab.add_widget(self.analysis_scroll)
        self.results_panel.add_widget(self.analysis_tab)
        self.main_layout.add_widget(self.results_panel)

        # 3. Action Buttons
        search_button = Button(text='Search', size_hint_y=None, height=50)
        search_button.bind(on_press=self.perform_search)
        self.main_layout.add_widget(search_button)
        settings_button = Button(text='Settings', size_hint_y=None, height=50)
        settings_button.bind(on_press=self.go_to_settings)
        self.main_layout.add_widget(settings_button)
        self.add_widget(self.main_layout)

    def go_to_settings(self, instance):
        self.manager.current = 'settings'

    def show_file_chooser(self, instance):
        content = BoxLayout(orientation='vertical', spacing=10)
        app = App.get_running_app()
        enabled_filters = [f"*{ext}" for ext, is_active in app.file_filters.items() if is_active]
        self.file_chooser = FileChooserIconView(
            path=os.path.expanduser("~"),
            filters=enabled_filters,
            entry_template='ThumbnailFileChooserEntry'
        )
        content.add_widget(self.file_chooser)
        button_layout = BoxLayout(size_hint_y=None, height=50, spacing=10)
        select_button = Button(text="Select")
        select_button.bind(on_press=self.select_file)
        cancel_button = Button(text="Cancel")
        cancel_button.bind(on_press=lambda x: self.popup.dismiss())
        button_layout.add_widget(select_button)
        button_layout.add_widget(cancel_button)
        content.add_widget(button_layout)
        self.popup = Popup(title="Choose an Image", content=content, size_hint=(0.9, 0.9))
        self.popup.open()

    def select_file(self, instance):
        if self.file_chooser.selection:
            filepath = self.file_chooser.selection[0]
            self.upload_label.text = os.path.basename(filepath)
            self.selected_filepath = filepath
            self.popup.dismiss()

    def perform_search(self, instance):
        image_url = self.url_input.text
        image_path = self.selected_filepath
        if not image_url and not image_path:
            self.update_results_display("Please provide an image URL or upload a file.")
            return
        enabled_engines = [engine for engine, active in App.get_running_app().search_engines.items() if active]
        if not enabled_engines:
            self.update_results_display("Please enable at least one search engine in Settings.")
            return
        self.update_results_display("Searching...")
        threading.Thread(target=self.search_and_process_results, args=(image_path, image_url, enabled_engines)).start()

    def search_and_process_results(self, image_path, image_url, engines):
        results = reverse_image_search(image_path=image_path, image_url=image_url, engines=engines)
        Clock.schedule_once(lambda dt: self.display_links(results))
        self.process_scraped_data(results)

    def process_scraped_data(self, links):
        Clock.schedule_once(lambda dt: self.summary_layout.clear_widgets())
        Clock.schedule_once(lambda dt: self.analysis_layout.clear_widgets())
        for link in links:
            full_text, error = scrape_page_summary(link)
            if error:
                summary, details = error, {}
            else:
                summary = full_text[:500] + '...' if len(full_text) > 500 else full_text
                details = analyze_text_for_details(full_text)
            Clock.schedule_once(lambda dt, l=link, s=summary: self.add_summary_widget(l, s))
            if details:
                Clock.schedule_once(lambda dt, l=link, d=details: self.add_analysis_widget(l, d))

    def add_analysis_widget(self, link, details):
        box = BoxLayout(orientation='vertical', size_hint_y=None, spacing=5, padding=5)
        box.add_widget(Label(text=link, bold=True, size_hint_y=None, height=30))
        content_box = BoxLayout(orientation='vertical', size_hint_y=None, spacing=2)
        content_box.bind(minimum_height=content_box.setter('height'))
        has_content = False
        if details.get('emails'):
            has_content = True
            content_box.add_widget(Label(text="Emails: " + ", ".join(details['emails']), text_size=(self.analysis_layout.width-40, None), size_hint_y=None))
        if details.get('phones'):
            has_content = True
            content_box.add_widget(Label(text="Phones: " + ", ".join(details['phones']), text_size=(self.analysis_layout.width-40, None), size_hint_y=None))
        if details.get('socials'):
            has_content = True
            for site, links in details['socials'].items():
                content_box.add_widget(Label(text=f"{site.capitalize()}: " + ", ".join(links), text_size=(self.analysis_layout.width-40, None), size_hint_y=None))
        if not has_content:
            content_box.add_widget(Label(text="No specific details found.", text_size=(self.analysis_layout.width-40, None), size_hint_y=None))
        box.add_widget(content_box)
        self.analysis_layout.add_widget(box)

    def add_summary_widget(self, link, summary):
        box = BoxLayout(orientation='vertical', size_hint_y=None, spacing=5, padding=10)
        box.bind(minimum_height=box.setter('height'))
        link_label = Label(text=link, bold=True, size_hint_y=None, height=30, text_size=(self.summary_layout.width-40, None))
        summary_label = Label(text=summary, text_size=(self.summary_layout.width-40, None), size_hint_y=None)
        summary_label.bind(texture_size=summary_label.setter('size'))
        box.add_widget(link_label)
        box.add_widget(summary_label)
        self.summary_layout.add_widget(box)

    def display_links(self, links):
        self.results_layout.clear_widgets()
        if not links:
            self.results_layout.add_widget(Label(text="No results found."))
            return
        for link in links:
            btn = Button(text=link, size_hint_y=None, height=40, on_release=self.open_link)
            btn.text = link[:100] + '...' if len(link) > 100 else link
            btn.link_data = link
            self.results_layout.add_widget(btn)

    def open_link(self, instance):
        webbrowser.open(instance.link_data)

    def update_results_display(self, message):
        self.results_layout.clear_widgets()
        self.results_layout.add_widget(Label(text=message))
        self.summary_layout.clear_widgets()
        self.summary_layout.add_widget(Label(text=''))
        self.analysis_layout.clear_widgets()
        self.analysis_layout.add_widget(Label(text=''))

class SettingsScreen(Screen):
    def __init__(self, **kwargs):
        super(SettingsScreen, self).__init__(**kwargs)
        self.app = App.get_running_app()
        layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        layout.add_widget(Label(text="Settings Panel", size_hint_y=0.1, font_size='20sp'))
        layout.add_widget(Label(text="Search Engines", size_hint_y=None, height=40, bold=True))
        engines_layout = GridLayout(cols=2, size_hint_y=0.4)
        for engine_name in sorted(self.app.search_engines.keys()):
            engines_layout.add_widget(Label(text=engine_name.capitalize()))
            cb = CheckBox(active=self.app.search_engines[engine_name])
            cb.bind(active=lambda instance, value, name=engine_name: self.on_engine_checkbox_active(instance, value, name))
            engines_layout.add_widget(cb)
        layout.add_widget(engines_layout)
        layout.add_widget(Label(text="File Type Filters", size_hint_y=None, height=40, bold=True))
        filters_layout = GridLayout(cols=4, size_hint_y=0.4)
        for ext_filter in sorted(self.app.file_filters.keys()):
            filters_layout.add_widget(Label(text=ext_filter))
            cb = CheckBox(active=self.app.file_filters[ext_filter])
            cb.bind(active=lambda instance, value, name=ext_filter: self.on_filter_checkbox_active(instance, value, name))
            filters_layout.add_widget(cb)
        layout.add_widget(filters_layout)
        back_button = Button(text="Back to Main Screen", size_hint_y=0.1)
        back_button.bind(on_press=self.go_to_main)
        layout.add_widget(back_button)
        self.add_widget(layout)

    def on_engine_checkbox_active(self, checkbox, value, engine_name):
        self.app.search_engines[engine_name] = value

    def on_filter_checkbox_active(self, checkbox, value, filter_name):
        self.app.file_filters[filter_name] = value

    def go_to_main(self, instance):
        self.manager.current = 'main'

class FaceFinderApp(App):
    def build(self):
        self.search_engines = {'google': True, 'yandex': True}
        self.file_filters = {'.jpg': True, '.jpeg': True, '.png': True, '.bmp': False}
        sm = ScreenManager()
        sm.add_widget(MainScreen(name='main'))
        sm.add_widget(SettingsScreen(name='settings'))
        return sm

if __name__ == '__main__':
    FaceFinderApp().run()