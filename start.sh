#!/bin/bash

# Activate the virtual environment if it exists
if [ -d "venv" ]; then
    source venv/bin/activate
fi

# Install dependencies
pip install -r requirements.txt

# Run database migrations
alembic upgrade head

# Start the application
exec uvicorn fin:app --host 0.0.0.0 --port $PORT
