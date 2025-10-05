import requests
from bs4 import BeautifulSoup
import os

class WebScraper:
    def __init__(self):
        """
        Initializes the scraper.
        """
        pass

    def search_by_image(self, image_path):
        """
        Performs a reverse image search on Google by directly uploading the image file.

        Args:
            image_path (str): The path to a local image file.

        Returns:
            list[str]: A list of URLs from the search results page.
        """
        if not os.path.exists(image_path):
            print(f"Error: Image path does not exist: {image_path}")
            return []

        print(f"Searching Google for image at: {image_path}")

        # This is the endpoint for Google's reverse image search upload
        search_url = "https://images.google.com/searchbyimage/upload"

        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        }

        try:
            with open(image_path, "rb") as f:
                # The name 'encoded_image' is what Google's form expects
                multipart = {'encoded_image': (os.path.basename(image_path), f), 'image_content': ''}
                response = requests.post(search_url, files=multipart, headers=headers, allow_redirects=False)

            response.raise_for_status()

            # The response to the upload is a redirect to the results page
            results_url = response.headers.get('Location')
            if not results_url:
                print("Failed to get results page URL from Google.")
                return []

            # Now, we fetch the results page
            results_response = requests.get(results_url, headers=headers)
            results_response.raise_for_status()

            soup = BeautifulSoup(results_response.text, 'html.parser')

            result_links = []
            # Find all links that seem to be search results
            for a_tag in soup.find_all('a', href=True):
                # This is a simple filter for external links that are likely results
                if a_tag['href'].startswith('http') and 'google.com' not in a_tag['href']:
                    result_links.append(a_tag['href'])

            # Remove duplicates by converting to a set and back to a list
            return list(set(result_links))

        except requests.RequestException as e:
            print(f"An error occurred during Google Images search: {e}")
            return []

# Example usage (for testing purposes)
if __name__ == '__main__':
    # Create a dummy image file for testing
    if not os.path.exists("temp"):
        os.makedirs("temp")
    dummy_image_path = "temp/test_image.png"
    try:
        from PIL import Image
        # Create a small, simple image
        img = Image.new('RGB', (100, 100), color = 'blue')
        img.save(dummy_image_path)
        print(f"Created a dummy image at: {dummy_image_path}")
    except ImportError:
        # If PIL is not installed, create a simple text file as a placeholder
        with open(dummy_image_path, "w") as f:
            f.write("this is a test")
        print("PIL not found. Created a dummy text file instead.")

    scraper = WebScraper()
    abs_path = os.path.abspath(dummy_image_path)
    print(f"Testing with local file: {abs_path}")

    results = scraper.search_by_image(abs_path)

    print(f"\nFound {len(results)} unique results:")
    # Print the first 10 for brevity
    for url in results[:10]:
        print(url)