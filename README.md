# CloudyCam
"A lightweight app, serving as a front end to SSH: For fast and secure upload of media via SSH/SFTP, from a phone"

## Features

- 🔒 **Secure Upload**: Utilizes SSH protocol for secure communication with SFTP servers, ensuring data integrity and confidentiality.
- ⏩ **Lightweight**: Lightweight design minimizing resource consumption on the device.
- 😸**User-friendly Interface**: Simple intuitive interface, easy for users to configure server settings and initiate file transfers.
- 🗃️ **Supports Various Media Formats**: Compatible with a wide range of video and image formats.
- 🧰**Customizable Settings**: Users can customize settings such as server hostname, port, username, and authentication method to suit their specific requirements.

## Requirements

- 📱 Android device running Android 10 (API level 29). or higher.
- 🖥️ An already existing SFTP server for the client to connect to.

## Installation

1. ⏬ Download the latest APK file from the [releases](https://github.com/your-username/CloudyCam/releases) section of this repository.
2. 📂 On your Android device, navigate to the location of the APK file using a file manager.
3. 💿Tap on the APK file to begin the installation process.
4. ▶️Follow the on-screen instructions to complete the installation.

## Usage

1. ▶️Launch the CloudyCam app on your Android device.
2. 🛂Permission is needed on first launch for the gallery "DCIM" for saving photos and Camera for the app to work!
3. 📸Snap a photo if you wish or (press back to upload your own).
4. 🧭Configure the server settings by entering the:
   username, password, hostname, port (eg.22)
   and a valid folder path to your output directory on the server:
   (eg. /Media/Username/)
5. 🔐You can toggle between authentication method (password or SSH key).
6. 🔑Upload a keyfile to use key based authenticatication (e.g. rsa/.pem file/SSH key etc.)
7. ➕Select the media files you wish to upload from your device using the (📎) symbol on the right hand side of the screen.
8. 💾Tap the "Upload" button indicated by the 'SAVE & UPLOAD' ICON to initiate the file transfer process using SSH.
9. 📊Monitor the progress of the upload using the progress bar.
10. 👌Rinse and repeat, as desired.

## Contributing

Contributions to CloudyCam are welcome, Especially in the early days of the project! 
If you encounter any bugs, have feature requests, or would like to contribute code improvements, please feel free to open an issue or submit a pull request on [GitHub](https://github.com/your-username/CloudyCam).

## License

This project is licens

Built on Jsch.

#known bugs
User interface is locked to portrait mode

Copyright (c) 2024 Glyndwr Gimblett

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software exclusively for personal, educational, or non-commercial use. 
Commercial use, redistribution, modification, or any other form of exploitation 
of the Software, in part or in whole, is strictly prohibited without prior written 
permission from Glyndwr Gimblett

Any distribution of the Software, whether in its original form or modified, must
include this license notice, the copyright notice, and acknowledgement of Glyndwr Gimblett
as the original creator of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
