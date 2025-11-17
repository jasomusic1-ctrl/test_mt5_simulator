# MT5 Trading Simulator

A multi-account trading simulator with WebSocket support, built with FastAPI and React.

## Features

- Multiple account types (VIP, DEMO, PRO, MONEY)
- Real-time WebSocket updates
- Trade simulation with configurable parameters
- User authentication and authorization
- Responsive web interface

## Prerequisites

- Python 3.9+
- pip
- Node.js 14+ (for frontend development)

## Local Development Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd mt5-simulator
   ```

2. **Set up Python environment**
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install -r requirements.txt
   ```

3. **Set up environment variables**
   ```bash
   cp .env.example .env
   # Edit .env with your configuration
   ```

4. **Initialize the database**
   ```bash
   python -c "from frn import Base, get_engine; Base.metadata.create_all(bind=get_engine())"
   ```

5. **Run the development server**
   ```bash
   uvicorn frn:app --reload
   ```

6. **Access the application**
   - Frontend: http://localhost:8000
   - API Docs: http://localhost:8000/docs

## Deployment to Render

1. **Create a new Web Service**
   - Go to [Render Dashboard](https://dashboard.render.com/)
   - Click "New" → "Web Service"
   - Connect your GitHub repository

2. **Configure the service**
   - Name: `mt5-simulator`
   - Region: Choose the one closest to you
   - Branch: `main`
   - Build Command: `pip install -r requirements.txt`
   - Start Command: `gunicorn frn:app --worker-class uvicorn.workers.UvicornWorker --workers 4 --bind 0.0.0.0:$PORT`

3. **Set environment variables**
   - `SECRET_KEY`: A secure secret key for JWT
   - `DATABASE_URL`: Your database connection string
   - `ADMIN_USERNAME`: Admin username
   - `ADMIN_PASSWORD`: Admin password
   - `PYTHON_VERSION`: 3.9.18
   - `PYTHONUNBUFFERED`: true

4. **Deploy**
   - Click "Create Web Service"
   - Monitor the build and deployment logs

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SECRET_KEY` | Secret key for JWT token signing | - |
| `ALGORITHM` | JWT algorithm | HS256 |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | Token expiration time in minutes | 1440 |
| `DATABASE_URL` | Database connection URL | sqlite:///./trades.db |
| `CORS_ORIGINS` | Allowed CORS origins (comma-separated) | * |
| `DEFAULT_ACCOUNT_BALANCE` | Starting balance for new accounts | 10000.0 |
| `TZ` | Timezone | UTC |

## Project Structure

```
.
├── frn.py              # Main FastAPI application
├── frontend/           # Frontend React application
├── requirements.txt    # Python dependencies
├── runtime.txt        # Python version
├── Procfile           # Process file for Render
└── README.md          # This file
```

## License

This project is licensed under the MIT License.
