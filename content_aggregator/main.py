import googlesearch
import requests
import webbrowser
import os
from bs4 import BeautifulSoup
from rich.console import Console
from rich.prompt import Prompt, IntPrompt
from rich.progress import Progress
from urllib.parse import urljoin, unquote, urlparse

console = Console()

def search_websites(query: str, num_results: int = 10) -> list[str]:
    """
    Searches Google for a given query and returns a list of URLs.
    """
    console.print(f"Searching for websites about '{query}'...")
    try:
        search_results = googlesearch.search(query, num_results=num_results)
        urls = [str(result) for result in search_results]
        return urls
    except Exception as e:
        console.print(f"[bold red]An error occurred during search: {e}[/bold red]")
        return []

def scrape_for_media(url: str, content_type: str) -> list[str]:
    """
    Scrapes a website for media links (images or videos).

    Args:
        url: The URL of the website to scrape.
        content_type: The type of media to search for ('images' or 'videos').

    Returns:
        A list of absolute URLs to the found media.
    """
    console.print(f"\nScraping '{url}' for {content_type}...")
    try:
        response = requests.get(url, timeout=10, headers={'User-Agent': 'Mozilla/5.0'})
        response.raise_for_status()
    except requests.RequestException as e:
        console.print(f"[bold red]Error fetching website: {e}[/bold red]")
        return []

    soup = BeautifulSoup(response.text, 'html.parser')
    media_urls = set()

    if content_type == 'images':
        tags = soup.find_all('img')
        for tag in tags:
            # Also check for 'data-src' for lazy-loaded images
            src = tag.get('src') or tag.get('data-src')
            if src:
                media_urls.add(urljoin(url, src))
    elif content_type == 'videos':
        # Find video tags
        video_tags = soup.find_all('video')
        for tag in video_tags:
            # Check for a 'src' attribute on the video tag itself
            if tag.get('src'):
                media_urls.add(urljoin(url, tag.get('src')))
            # Check for source tags within the video tag
            for source in tag.find_all('source'):
                if source.get('src'):
                    media_urls.add(urljoin(url, source.get('src')))

    return list(media_urls)


def download_file(url: str, folder: str = "downloads"):
    """
    Downloads a file from a URL to a specified folder.
    """
    os.makedirs(folder, exist_ok=True)
    try:
        with requests.get(url, stream=True, timeout=15, headers={'User-Agent': 'Mozilla/5.0'}) as r:
            r.raise_for_status()

            # Try to get filename from Content-Disposition header
            content_disposition = r.headers.get('content-disposition')
            if content_disposition:
                import re
                fname = re.findall('filename="?(.+)"?', content_disposition)[0]
                filename = os.path.join(folder, unquote(fname))
            else:
                # Fallback to URL parsing
                path = urlparse(url).path
                filename = os.path.join(folder, unquote(os.path.basename(path)))

            if not os.path.basename(filename): # If no filename found
                 console.print("[yellow]Could not determine filename. Using 'downloaded_file'.[/yellow]")
                 filename = os.path.join(folder, "downloaded_file")

            total_size = int(r.headers.get('content-length', 0))

            with Progress() as progress:
                task = progress.add_task(f"[cyan]Downloading {os.path.basename(filename)}...", total=total_size)
                with open(filename, 'wb') as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        f.write(chunk)
                        progress.update(task, advance=len(chunk))

            console.print(f"\n[bold green]File downloaded successfully to '{filename}'[/bold green]")

    except requests.RequestException as e:
        console.print(f"[bold red]Download failed: {e}[/bold red]")
    except Exception as e:
        console.print(f"[bold red]An unexpected error occurred during download: {e}[/bold red]")


def main():
    """
    Main function to run the content aggregator CLI.
    """
    console.print("[bold green]Welcome to the Content Aggregator Tool![/bold green]")

    topic = Prompt.ask("What topic are you interested in? (e.g., 'free stock photos')")
    urls = search_websites(topic)

    if not urls:
        console.print("[yellow]No websites found. Please try another topic.[/yellow]")
        return

    console.print("\n[bold]Here are the top websites I found:[/bold]")
    for i, url in enumerate(urls, 1):
        console.print(f"{i}. {url}")

    while True:
        choice = IntPrompt.ask("\nChoose a website to search within (enter number)", choices=[str(i) for i in range(1, len(urls) + 1)])
        selected_url = urls[choice - 1]

        # This part will be fully implemented in the next step
        content_type = Prompt.ask("What do you want to find?", choices=["images", "videos"], default="images")

        media_links = scrape_for_media(selected_url, content_type)

        console.print(f"\n[bold]Found {len(media_links)} {content_type}:[/bold]")
        if not media_links:
            console.print(f"[yellow]No {content_type} found on this page.[/yellow]")
        else:
            for i, link in enumerate(media_links, 1):
                # Truncate long links for display
                display_link = link if len(link) < 100 else link[:97] + "..."
                console.print(f"{i}. {display_link}")

            action = Prompt.ask("\nWhat next?", choices=["download", "nothing"], default="nothing")
            if action == "download":
                link_num = IntPrompt.ask("Enter link number to download", choices=[str(i) for i in range(1, len(media_links) + 1)])
                link_to_download = media_links[link_num - 1]
                download_file(link_to_download)

        if Prompt.ask("\nSearch another website?", choices=["y", "n"], default="n") == 'n':
            break

    console.print("\n[bold green]Thank you for using the Content Aggregator Tool![/bold green]")

if __name__ == "__main__":
    main()
