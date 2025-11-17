from setuptools import setup, find_packages

setup(
    name="mt5-simulator",
    version="1.0.0",
    packages=find_packages(),
    install_requires=[
        'fastapi>=0.68.0',
        'uvicorn[standard]>=0.15.0',
        'python-jose[cryptography]>=3.3.0',
        'python-multipart>=0.0.5',
        'sqlalchemy>=1.4.0',
        'pydantic>=1.8.0',
        'python-dotenv>=0.19.0',
        'websockets>=10.0',
        'aiofiles>=0.7.0',
        'python-dateutil>=2.8.2',
        'cryptography>=35.0.0',
        'pytz>=2021.3',
        'numpy>=1.21.0',
    ],
    python_requires='>=3.9',
)
