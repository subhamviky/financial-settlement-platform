# The P0 Python Dockerfile serves on port 8000; the A2C v1.1.0 template defaulted to 8080
# (fixed in A2C v1.1.2, which takes the port from the request).
container_port = 8000
