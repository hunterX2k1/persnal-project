import requests
from bs4 import BeautifulSoup
import re

def search_google(image_path=None, image_url=None):
    """
    Performs a reverse image search on Google and yields status messages and result URLs.
    """
    if not image_path and not image_url:
        return

    yield "LOG: Contacting Google Images..."
    search_url = 'http://www.google.com/searchbyimage/upload'
    headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36'}

    try:
        if image_path:
            multipart = {'encoded_image': (image_path, open(image_path, 'rb')), 'image_content': ''}
            response = requests.post(search_url, files=multipart, allow_redirects=False, timeout=10)
        else:
            multipart = {'image_url': image_url}
            response = requests.get(search_url, params=multipart, allow_redirects=False, timeout=10)
        response.raise_for_status()
    except requests.RequestException as e:
        yield f"ERROR: Failed to connect to Google: {e}"
        return

    fetch_url = response.headers.get('Location')
    if not fetch_url:
        yield "ERROR: Could not get search results URL from Google."
        return

    yield "LOG: Fetching Google results page..."
    try:
        response = requests.get(fetch_url, headers=headers, timeout=10)
        response.raise_for_status()
    except requests.RequestException as e:
        yield f"ERROR: Failed to fetch results from Google: {e}"
        return

    yield "LOG: Parsing Google results..."
    soup = BeautifulSoup(response.text, 'lxml')
    found_count = 0
    for a_tag in soup.find_all('a', {'class': 'GZrdsf'}):
        href = a_tag.get('href')
        if href and href.startswith('http'):
            yield {'type': 'result', 'url': href}
            found_count += 1

    if found_count == 0:
        yield "LOG: No results found on Google."

def search_yandex(image_path=None, image_url=None):
    """
    Performs a reverse image search on Yandex and yields status messages and result URLs.
    """
    if not image_path and not image_url:
        return

    yield "LOG: Contacting Yandex Images..."
    headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36'}

    try:
        if image_path:
            search_url = "https://yandex.com/images/search"
            files = {'upfile': ('blob', open(image_path, 'rb'), 'image/jpeg')}
            params = {'rpt': 'imageview', 'format': 'json', 'request': '{"blocks":[{"block":"b-page_type_search-by-image__link"}]}'}
            response = requests.post(search_url, params=params, files=files, timeout=10)
        else:
            search_url = "https://yandex.com/images/search"
            params = {'rpt': 'imageview', 'url': image_url}
            response = requests.get(search_url, params=params, headers=headers, timeout=10)
        response.raise_for_status()
    except requests.RequestException as e:
        yield f"ERROR: Failed to connect to Yandex: {e}"
        return

    yield "LOG: Parsing Yandex results..."
    soup = BeautifulSoup(response.text, 'lxml')
    found_count = 0
    for item in soup.select('.CbirSites-Item'):
        link_tag = item.select_one('.CbirSites-ItemTitle a')
        if link_tag and link_tag.get('href'):
            yield {'type': 'result', 'url': link_tag.get('href')}
            found_count += 1

    if found_count == 0:
        yield "LOG: No results found on Yandex."

def reverse_image_search(image_path=None, image_url=None, engines=['google', 'yandex']):
    """
    Performs a reverse image search using the specified engines and yields status/results.
    """
    if 'google' in engines:
        yield "LOG: --- Starting Google Search ---"
        yield from search_google(image_path, image_url)
    if 'yandex' in engines:
        yield "LOG: --- Starting Yandex Search ---"
        yield from search_yandex(image_path, image_url)

import re

def scrape_page_summary(url):
    """
    Scrapes the text content from a given URL and yields the full text or an error.
    """
    yield f"LOG: Scraping {url[:80]}..."
    try:
        headers = {
            'User-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36'
        }
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()

        soup = BeautifulSoup(response.text, 'lxml')

        for script in soup(["script", "style"]):
            script.extract()

        text = soup.get_text()
        lines = (line.strip() for line in text.splitlines())
        chunks = (phrase.strip() for line in lines for phrase in line.split("  "))
        text = '\n'.join(chunk for chunk in chunks if chunk)

        yield {'type': 'scrape_result', 'text': text}
    except requests.RequestException as e:
        yield f"ERROR: Failed to scrape {url[:80]}: {e}"
    except Exception as e:
        yield f"ERROR: An error occurred while processing {url[:80]}: {e}"

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