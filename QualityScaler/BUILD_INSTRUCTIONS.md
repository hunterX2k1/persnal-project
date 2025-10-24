# Build Instructions for the Video Upscaler Application

This guide will walk you through the process of setting up your environment, installing the necessary dependencies, and building the `VideoUpscaler.exe` file on your Windows machine.

## 1. Prerequisites

Before you begin, you will need to have the following software installed on your computer:

*   **Python:** This project requires Python 3.10 or newer. You can download the latest version of Python from the [official Python website](https://www.python.org/downloads/).
*   **Git:** You will need Git to clone the project repository. You can download Git from the [official Git website](https://git-scm.com/downloads).

## 2. Setup

First, you need to get the source code for the project. Open a command prompt or PowerShell and run the following command to clone the repository:

```bash
git clone <repository_url>
```

Once the repository is cloned, navigate into the project directory:

```bash
cd QualityScaler
```

## 3. Install Dependencies

This project uses several Python packages that need to be installed. You can install all of them at once using the `requirements.txt` file. Run the following command in your command prompt or PowerShell:

```bash
pip install -r requirements.txt
```

## 4. Build the Executable

Now you are ready to build the `.exe` file. This project uses `pyinstaller` to package the application into a single executable file. Run the following command in your command prompt or PowerShell:

```bash
pyinstaller --onefile --windowed --add-data "models/FSRCNN_x3.pb;models" gui.py
```

This command will create a `dist` directory. Inside the `dist` directory, you will find the `gui.exe` file. You can now run this file to start the Video Upscaler application.
