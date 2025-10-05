from deepface import DeepFace
import os
import requests
from PIL import Image
import numpy as np

class FaceAnalyzer:
    def __init__(self, logger=print):
        """
        Initializes the face analyzer using the DeepFace library.
        Args:
            logger (function): A function to call for logging messages. Defaults to print.
        """
        self.logger = logger
        # The first call to DeepFace downloads required models, so we can do it here
        # to ensure they are ready when needed. This is a silent pre-loading.
        try:
            self.logger("Loading facial recognition models...")
            _ = DeepFace.build_model("VGG-Face")
            self.logger("Models loaded successfully.")
        except Exception as e:
            self.logger(f"Could not pre-load DeepFace models: {e}")

    def find_matching_faces(self, source_image_path, image_urls_to_check):
        """
        Verifies which of the provided image URLs contain a face that matches the source image.

        Args:
            source_image_path (str): The file path of the image to search for.
            image_urls_to_check (list[str]): A list of URLs of images to check against.

        Returns:
            list[str]: A list of URLs that were verified as a match.
        """
        if not os.path.exists(source_image_path):
            self.logger(f"Error: Source image not found: {source_image_path}")
            return []

        matching_urls = []
        total_images = len(image_urls_to_check)

        for i, url in enumerate(image_urls_to_check):
            self.logger(f"Analyzing image {i+1} of {total_images}: {url[:70]}...")
            try:
                headers = {'User-Agent': 'Mozilla/5.0'}
                response = requests.get(url, headers=headers, timeout=15, stream=True)
                response.raise_for_status()

                img_array = np.array(Image.open(response.raw).convert('RGB'))

                result = DeepFace.verify(
                    img1_path=source_image_path,
                    img2_path=img_array,
                    enforce_detection=False,
                    detector_backend='retinaface'
                )

                if result.get("verified"):
                    self.logger(f"  -> MATCH FOUND!")
                    matching_urls.append(url)
                else:
                    self.logger(f"  -> No match.")

            except Exception as e:
                self.logger(f"  -> Could not verify image. Error: {e}")
                continue

        return matching_urls

# Example usage (for testing purposes)
if __name__ == '__main__':
    # This test requires real images with faces.
    # We will create placeholder files to ensure the script runs without syntax errors.
    print("Running a basic test of the FaceAnalyzer...")
    if not os.path.exists("temp"):
        os.makedirs("temp")

    source_img_path = "temp/source_face.jpg"
    # To properly test, you would replace this with an actual image of a face.
    Image.new('RGB', (200, 200), color = 'red').save(source_img_path)

    # In a real run, these URLs would come from the web_scraper
    # For this test, we'll use a known public image URL that contains a face.
    # (Using a celebrity face is a common way to test face detection)
    test_urls = [
        "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/Dwayne_Johnson_in_2018.jpg/800px-Dwayne_Johnson_in_2018.jpg", # Should not match our red square
        "https://www.nasa.gov/wp-content/uploads/2023/11/iss070e002631-large.jpg" # A picture of earth, no face
    ]

    analyzer = FaceAnalyzer()

    # We can't compare a red square to a real face and expect a match.
    # The purpose of this test is to ensure the code runs and calls DeepFace correctly.
    # The expected output is "0 matching URLs found."
    print(f"\nSearching for matches to '{source_img_path}' in the following URLs:")
    print("\n".join(test_urls))

    matches = analyzer.find_matching_faces(source_img_path, test_urls)

    print(f"\nTest complete. Found {len(matches)} matching URLs.")
    if matches:
        print("Matches found:")
        for url in matches:
            print(url)