# How to Build the FaceFinder Executable

This guide will walk you through the simple steps required to package the FaceFinder application into a standalone executable (`.exe`) on your own Windows machine.

## Prerequisites

- **Python:** You must have Python installed. You can download it from [python.org](https://www.python.org/downloads/). During installation, make sure to check the box that says **"Add Python to PATH."**
- **Git (Optional):** If you have Git, you can clone the repository. Otherwise, you can download the source code as a ZIP file.

## Step 1: Get the Source Code

Download and extract the project files to a folder on your computer.

## Step 2: Open a Command Prompt

Navigate to the project folder. The easiest way to do this is to open the folder in File Explorer, right-click anywhere in the window while holding the `Shift` key, and select **"Open PowerShell window here"** or **"Open command window here."**

## Step 3: Install Dependencies

In the command window you just opened, run the following command to install all the necessary Python libraries. This may take a few minutes.

```
pip install -r requirements.txt
```

After that completes, install PyInstaller, the tool that will build the executable:

```
pip install pyinstaller
```

## Step 4: Run the Build Command

Now, run the following command in the same window. This will start the packaging process. It may take several minutes to complete, and you will see a lot of output in the console.

```
pyinstaller --name FaceFinder --onefile --windowed main.py
```

## Step 5: Find Your Executable

Once the command finishes, you will find a new folder named `dist` inside your project directory. Open it, and you will see `FaceFinder.exe`.

You can now run this file to start the application! You can move this `.exe` file to another location on your computer, but it must be accompanied by the other files and folders that PyInstaller created in the `dist` directory if you did not use the `--onefile` flag. If you used `--onefile`, the single `.exe` is all you need.

That's it! You have successfully built the application.