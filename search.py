import requests
from bs4 import BeautifulSoup

def search_google(image_path=None, image_url=None):
    """
    Performs a reverse image search using Google Images and returns the result URLs.
    """
    if not image_path and not image_url:
        return []

    search_url = 'http://www.google.com/searchbyimage/upload'
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36'
    }

    # Get the redirect URL from Google
    if image_path:
        multipart = {'encoded_image': (image_path, open(image_path, 'rb')), 'image_content': ''}
        response = requests.post(search_url, files=multipart, allow_redirects=False)
    else:  # image_url
        multipart = {'image_url': image_url}
        response = requests.get(search_url, params=multipart, allow_redirects=False)

    fetch_url = response.headers.get('Location')
    if not fetch_url:
        return ["Could not get search results URL from Google."]

    # Fetch the actual results page
    response = requests.get(fetch_url, headers=headers)
    soup = BeautifulSoup(response.text, 'lxml')

    # Scrape the result links
    results = []
    # This selector targets the main search result links in Google's "Visually similar images" section
    for a_tag in soup.find_all('a', {'class': 'GZrdsf'}):
        href = a_tag.get('href')
        if href and href.startswith('http'):
            results.append(href)

    if not results:
        return ["No results found or page structure has changed."]

    return results

def search_yandex(image_path=None, image_url=None):
    """
    Performs a reverse image search using Yandex Images and returns the result URLs.
    """
    if not image_path and not image_url:
        return []

    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36'
    }

    if image_path:
        search_url = "https://yandex.com/images/search"
        files = {'upfile': ('blob', open(image_path, 'rb'), 'image/jpeg')}
        params = {'rpt': 'imageview', 'format': 'json', 'request': '{"blocks":[{"block":"b-page_type_search-by-image__link"}]}'}
        response = requests.post(search_url, params=params, files=files)
    else: # image_url
        search_url = "https://yandex.com/images/search"
        params = {'rpt': 'imageview', 'url': image_url}
        response = requests.get(search_url, params=params, headers=headers)

    if response.status_code != 200:
        return ["Failed to get a response from Yandex."]

    # Yandex results are trickier. We will try to find links in the HTML that point to pages with the image.
    soup = BeautifulSoup(response.text, 'lxml')
    results = []
    # This selector targets links within the "Sites with this image" section on Yandex
    for item in soup.select('.CbirSites-Item'):
        link_tag = item.select_one('.CbirSites-ItemTitle a')
        if link_tag and link_tag.get('href'):
            results.append(link_tag.get('href'))

    if not results:
        return ["No results found on Yandex or page structure has changed."]

    return results

def reverse_image_search(image_path=None, image_url=None, engines=['google', 'yandex']):
    """
    Performs a reverse image search using the specified engines.
    """
    all_results = []
    if 'google' in engines:
        all_results.extend(search_google(image_path, image_url))
    if 'yandex' in engines:
        all_results.extend(search_yandex(image_path, image_url))

    return all_results

import re

def scrape_page_summary(url):
    """
    Scrapes the text content from a given URL and returns the full text.
    """
    try:
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36'
        }
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status() # Raise an exception for bad status codes

        soup = BeautifulSoup(response.text, 'lxml')

        # Remove script and style elements
        for script in soup(["script", "style"]):
            script.extract()

        text = soup.get_text()
        lines = (line.strip() for line in text.splitlines())
        chunks = (phrase.strip() for line in lines for phrase in line.split("  "))
        text = '\n'.join(chunk for chunk in chunks if chunk)

        return text, None # Return text and no error
    except requests.RequestException as e:
        return None, f"Error fetching {url}: {e}"
    except Exception as e:
        return None, f"An error occurred while processing {url}: {e}"

def analyze_text_for_details(text):
    """
    Analyzes text to find emails, phone numbers, and potential social media links.
    """
    if not text:
        return {}

    details = {
        'emails': list(set(re.findall(r'[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}', text))),
        'phones': list(set(re.findall(r'\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}', text))),
        'socials': {
            'facebook': list(set(re.findall(r'https?://(?:www\.)?facebook\.com/[\w.-]+', text))),
            'twitter': list(set(re.findall(r'https?://(?:www\.)?twitter\.com/[\w.-]+', text))),
            'instagram': list(set(re.findall(r'https?://(?:www\.)?instagram\.com/[\w.-]+', text))),
            'linkedin': list(set(re.findall(r'https?://(?:www\.)?linkedin\.com/in/[\w.-]+', text))),
        }
    }
    # Filter out empty lists from socials
    details['socials'] = {k: v for k, v in details['socials'].items() if v}

    return details