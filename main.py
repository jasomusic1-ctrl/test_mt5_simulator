import os
from dotenv import load_dotenv

# Load environment variables from .env file if it exists
load_dotenv()

# Import required libraries
try:
    from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Depends, status
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm, HTTPBearer, HTTPAuthorizationCredentials
    from fastapi.responses import HTMLResponse
    from fastapi.staticfiles import StaticFiles
    from jose import JWTError, jwt
    import hashlib
    from datetime import datetime, timedelta, timezone
    from typing import List, Dict, Optional
    from pydantic import BaseModel, Field, field_validator, ValidationInfo, ConfigDict
    from enum import Enum
    import asyncio
    import json
    import uuid
    import random
    import numpy as np
    from sqlalchemy import create_engine, Column, String, Float, DateTime, Enum as SQLAlchemyEnum, text
    from sqlalchemy.orm import declarative_base, sessionmaker, Session
    from sqlalchemy.exc import IntegrityError
    from contextlib import asynccontextmanager
    import pytz
    import logging
except ImportError as e:
    print(f"Missing required library: {e}")
    print("Please install required packages with: pip install -r requirements.txt")
    exit(1)

async def lifespan(app: FastAPI):
    # Startup - create background tasks for all accounts
    for account_type in ACCOUNT_TYPES:
        asyncio.create_task(account_simulate_trades(account_type))
        asyncio.create_task(auto_save_trades_to_database(account_type))
    
    # Load balances from file if available
    load_account_balances()    

    print("✅ Background tasks started: Trade simulation and auto-save (every 30s)")
    yield
    # Shutdown (if needed)
    pass

app = FastAPI(title="MetaTrader 5 Simulator API - Multi-Account Edition", lifespan=lifespan)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount static files (frontend) at root
# During Render build, frontend.html is copied to static/index.html
static_dir = os.path.join(os.path.dirname(__file__), "static")
if os.path.exists(static_dir):
    app.mount("/", StaticFiles(directory=static_dir, html=True), name="static")

# Database setup - separate database for each account
def get_database_url(account_type: str) -> str:
    # In production (Render), use absolute path for SQLite
    if os.getenv('RENDER'):
        db_path = os.path.join(os.getcwd(), f'trades_{account_type.lower()}.db')
        return f"sqlite:///{db_path}"
    # For local development
    return f"sqlite:///trades_{account_type.lower()}.db"

# Create engines and sessions for each account
account_engines = {}
account_session_makers = {}

Base = declarative_base()

# Security configurations
SECRET_KEY = "your-secret-key-please-change-in-production"
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = None

# OAuth2 schemes
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token", auto_error=False)
security = HTTPBearer(auto_error=False)

# Constants
CURRENCY_PAIRS = ["EURUSD", "GBPUSD", "USDJPY", "USDCNH", "USDRUB", "AUDUSD", "NZDUSD", "USDSEK"]
DEFAULT_ACCOUNT_BALANCE = 10000.0
DEFAULT_LEVERAGE = 100
SWAP_RATE_BUY = -0.0001
SWAP_RATE_SELL = 0.00005
COMMISSION = 0.0001
ACCOUNT_TYPES = ["VIP", "DEMO", "PRO", "MONEY"]

# Global session state
LICENSE_KEY = "1234"
session_state = {
    "current_account": "VIP",
    "current_user": "public_user",
    "last_switch_time": None
}

# Global timezone setting - default to UTC
SYSTEM_TIMEZONE = "UTC"

# Password hashing
def hash_password(password: str) -> str:
    return hashlib.sha256(password.encode()).hexdigest()

def verify_password(plain_password: str, hashed_password: str) -> bool:
    return hash_password(plain_password) == hashed_password

ADMIN_PASSWORD_HASH = hash_password("secret")

# Enums
class TradeDirection(str, Enum):
    BUY = "BUY"
    SELL = "SELL"

class TradeStatus(str, Enum):
    RUNNING = "RUNNING"
    STOPPED = "STOPPED"
    COMPLETED = "COMPLETED"

class TargetType(str, Enum):
    PROFIT = "PROFIT"
    LOSS = "LOSS"

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

# Models
class Token(BaseModel):
    access_token: Optional[str] = None
    token_type: str = "none"
    role: str = "PUBLIC"
    message: str = "All endpoints are public."

class TokenData(BaseModel):
    username: Optional[str] = None
    role: UserRole = UserRole.USER
    is_first_admin: bool = False

class UserInfo(BaseModel):
    role: UserRole
    username: Optional[str] = None
    is_first_admin: bool = False
    admin_occupied: bool = False
    admin_login_time: Optional[datetime] = None
    current_account: str = "VIP"

class CurrencyPairConfig(BaseModel):
    symbol: str
    buy_starting_price: float = Field(..., gt=0, description="Starting buy price (BID)")
    buy_lot_size: float = Field(0.1, gt=0, le=100, description="Buy lot size in standard lots")
    default_target_profit: float = Field(100.0, ge=0, description="Default target profit in USD (0 if unset)")
    default_target_loss: float = Field(50.0, ge=0, description="Default target loss in USD (0 if unset)")
    sell_starting_price: float = Field(..., gt=0, description="Starting sell price (ASK)")
    sell_lot_size: float = Field(0.1, gt=0, le=100, description="Sell lot size in standard lots")
    volatility: float = Field(0.00005, gt=0, description="Price volatility for simulation")
    spread: float = Field(0.0002, gt=0, description="Spread between bid and ask")
    pip_value: float = Field(0.0001, gt=0, description="Pip value for the pair")
    buy_enabled: bool = Field(True, description="Whether buying is enabled")
    sell_enabled: bool = Field(True, description="Whether selling is enabled")
    mean_reversion_strength: float = Field(0.05, gt=0, le=1, description="Strength of mean reversion for price simulation")

    @field_validator('sell_starting_price')
    @classmethod
    def sell_price_must_be_greater_than_buy(cls, v, info: ValidationInfo):
        if 'buy_starting_price' in info.data and v <= info.data['buy_starting_price']:
            raise ValueError('Sell price must be greater than buy price')
        return v

class AccountMetrics(BaseModel):
    balance: float = DEFAULT_ACCOUNT_BALANCE
    equity: float = DEFAULT_ACCOUNT_BALANCE
    margin: float = 0.0
    free_margin: float = DEFAULT_ACCOUNT_BALANCE
    margin_level: float = 0.0
    profit: float = 0.0
    total_swap: float = 0.0
    total_profit_loss: float = 0.0

class TradeData(BaseModel):
    trade_id: str
    symbol: str
    entry_price: float
    current_buy_price: float
    current_sell_price: float
    start_time: datetime
    end_time: Optional[datetime] = None
    status: TradeStatus = TradeStatus.RUNNING
    target_price: float
    target_type: TargetType
    target_amount: float
    lot_size: float
    trade_direction: TradeDirection
    profit_loss: float = 0.0
    margin_used: float = 0.0
    swap: float = 0.0
    commission: float = 0.0
    bias_factor: float = Field(0.0, ge=0.0, le=1.0, description="Bias factor for price movement toward target")
    closing_price: Optional[float] = None

    def calculate_pnl(self, current_price: float) -> float:
        if self.trade_direction == TradeDirection.BUY:
            price_diff = current_price - self.entry_price
        else:
            price_diff = self.entry_price - current_price
        self.profit_loss = price_diff * self.lot_size * 100000
        if self.symbol.startswith("USD"):
            self.profit_loss /= current_price
        self.profit_loss -= (self.commission + self.swap)
        return self.profit_loss

# Database model for TradeData
class TradeDataDB(Base):
    __tablename__ = "trades"

    trade_id = Column(String, primary_key=True, index=True)
    symbol = Column(String, nullable=False)
    entry_price = Column(Float, nullable=False)
    current_buy_price = Column(Float, nullable=False)
    current_sell_price = Column(Float, nullable=False)
    start_time = Column(DateTime, nullable=False)
    end_time = Column(DateTime, nullable=True)
    status = Column(SQLAlchemyEnum(TradeStatus), nullable=False)
    target_price = Column(Float, nullable=False)
    target_type = Column(SQLAlchemyEnum(TargetType), nullable=False)
    target_amount = Column(Float, nullable=False)
    lot_size = Column(Float, nullable=False)
    trade_direction = Column(SQLAlchemyEnum(TradeDirection), nullable=False)
    profit_loss = Column(Float, default=0.0)
    margin_used = Column(Float, default=0.0)
    swap = Column(Float, default=0.0)
    commission = Column(Float, default=0.0)
    bias_factor = Column(Float, default=0.0)
    closing_price = Column(Float, nullable=True)

# Initialize databases for all accounts
def initialize_account_databases():
    for account_type in ACCOUNT_TYPES:
        db_url = get_database_url(account_type)
        # For SQLite in production, ensure the directory exists
        if os.getenv('RENDER') and db_url.startswith('sqlite'):
            db_path = db_url.replace('sqlite:///', '')
            os.makedirs(os.path.dirname(db_path), exist_ok=True)
            
        connect_args = {"check_same_thread": False} if db_url.startswith('sqlite') else {}
        engine = create_engine(db_url, connect_args=connect_args)
        
        # Create tables if they don't exist
        Base.metadata.create_all(bind=engine)
        
        account_engines[account_type] = engine
        account_session_makers[account_type] = sessionmaker(autocommit=False, autoflush=False, bind=engine)
        
        logging.info(f"Initialized database for {account_type} at {db_url}")
        try:
            with engine.connect() as conn:
                cols = [row[1] for row in conn.execute(text("PRAGMA table_info(trades)"))]
                if "closing_price" not in cols:
                    conn.execute(text("ALTER TABLE trades ADD COLUMN closing_price FLOAT"))
                    conn.commit()
        except Exception:
            pass

initialize_account_databases()

def get_db(account_type: str = "VIP"):
    """Get database session for specific account"""
    SessionLocal = account_session_makers.get(account_type)
    if not SessionLocal:
        raise HTTPException(status_code=400, detail=f"Invalid account type: {account_type}")
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

def load_account_balances():
    """Load account balances from file"""
    try:
        if os.path.exists("account_balances.json"):
            with open("account_balances.json", "r") as f:
                data = json.load(f)
                balances = data.get("accounts", {})
                for account_type in ACCOUNT_TYPES:
                    if account_type in balances:
                        balance = balances[account_type]
                        account_metrics_store[account_type] = AccountMetrics(
                            balance=balance, equity=balance, free_margin=balance
                        )
                print(f"✅ Loaded account balances from file")
    except Exception as e:
        print(f"⚠️  Could not load balances: {e}")

def get_current_time_in_timezone() -> datetime:
    tz = pytz.timezone(SYSTEM_TIMEZONE)
    return datetime.now(tz)

def convert_utc_to_system_timezone(utc_time: datetime) -> datetime:
    if utc_time.tzinfo is None:
        utc_time = pytz.utc.localize(utc_time)
    tz = pytz.timezone(SYSTEM_TIMEZONE)
    return utc_time.astimezone(tz)

# In-memory storage for each account
active_trades_store: Dict[str, Dict[str, TradeData]] = {acc: {} for acc in ACCOUNT_TYPES}
currency_pair_settings_store: Dict[str, Dict[str, CurrencyPairConfig]] = {}
account_metrics_store: Dict[str, AccountMetrics] = {}

# Initialize default settings for each account
for account_type in ACCOUNT_TYPES:
    currency_pair_settings_store[account_type] = {
        "EURUSD": CurrencyPairConfig(symbol="EURUSD", buy_starting_price=1.1726, sell_starting_price=1.1727, volatility=0.00005, spread=0.0002, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=100.0, default_target_loss=50.0, pip_value=0.0001, mean_reversion_strength=0.05),
        "GBPUSD": CurrencyPairConfig(symbol="GBPUSD", buy_starting_price=1.3577, sell_starting_price=1.3579, volatility=0.00005, spread=0.0003, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=100.0, default_target_loss=50.0, pip_value=0.0001, mean_reversion_strength=0.05),
        "USDJPY": CurrencyPairConfig(symbol="USDJPY", buy_starting_price=147.1960, sell_starting_price=147.2060, volatility=0.00005, spread=0.02, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=1000.0, default_target_loss=500.0, pip_value=0.01, mean_reversion_strength=0.0004),
        "USDCNH": CurrencyPairConfig(symbol="USDCNH", buy_starting_price=7.1823, sell_starting_price=7.1837, volatility=0.00005, spread=0.0020, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=500.0, default_target_loss=250.0, pip_value=0.0001, mean_reversion_strength=0.05),
        "USDRUB": CurrencyPairConfig(symbol="USDRUB", buy_starting_price=84.9900, sell_starting_price=85.0400, volatility=0.00005, spread=0.02, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=5000.0, default_target_loss=2500.0, pip_value=0.01, mean_reversion_strength=0.0007),
        "AUDUSD": CurrencyPairConfig(symbol="AUDUSD", buy_starting_price=0.6639, sell_starting_price=0.6641, volatility=0.00005, spread=0.0002, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=100.0, default_target_loss=50.0, pip_value=0.0001, mean_reversion_strength=0.05),
        "NZDUSD": CurrencyPairConfig(symbol="NZDUSD", buy_starting_price=0.5975, sell_starting_price=0.5977, volatility=0.00005, spread=0.0002, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=100.0, default_target_loss=50.0, pip_value=0.0001, mean_reversion_strength=0.05),
        "USDSEK": CurrencyPairConfig(symbol="USDSEK", buy_starting_price=9.3673, sell_starting_price=9.3873, volatility=0.00005, spread=0.02, buy_lot_size=0.1, sell_lot_size=0.1, default_target_profit=1000.0, default_target_loss=500.0, pip_value=0.01, mean_reversion_strength=0.05)
    }
    account_metrics_store[account_type] = AccountMetrics(balance=DEFAULT_ACCOUNT_BALANCE, equity=DEFAULT_ACCOUNT_BALANCE, free_margin=DEFAULT_ACCOUNT_BALANCE)

# WebSocket manager
class ConnectionManager:
    def __init__(self):
        self.active_connections: List[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        self.active_connections.remove(websocket)

    async def broadcast(self, message: str):
        for connection in self.active_connections:
            try:
                await connection.send_text(message)
            except:
                pass

manager = ConnectionManager()

# Authentication functions
def create_access_token(data: dict, expires_delta: Optional[timedelta] = None):
    to_encode = data.copy()
    if expires_delta is not None:
        expire = datetime.now(timezone.utc) + expires_delta
        to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
    return encoded_jwt

async def get_current_user(token: str = Depends(oauth2_scheme)):
    return TokenData(username=None, role=UserRole.USER, is_first_admin=False)

async def get_current_user_optional(credentials: Optional[HTTPAuthorizationCredentials] = Depends(security)):
    """Get current user with optional authentication - allows both authenticated and unauthenticated access"""
    if not credentials:
        return TokenData(username=None, role=UserRole.USER, is_first_admin=False)
    
    try:
        token = credentials.credentials
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username: str = payload.get("sub")
        return TokenData(username=username, role=UserRole.USER, is_first_admin=False)
    except JWTError:
        return TokenData(username=None, role=UserRole.USER, is_first_admin=False)

def get_pip_value_for_symbol(symbol: str) -> float:
    """Return the pip value for a given currency pair."""
    if symbol.endswith("JPY") or symbol.endswith("RUB") or symbol.endswith("SEK"):
        return 0.01
    return 0.0001

def generate_mt5_price(previous_price: float, volatility: float, mean_price: float, 
                      mean_reversion_strength: float, target_price: float, bias_factor: float, 
                      trade_direction: TradeDirection, target_type: TargetType,
                      symbol: str) -> float:
    """
    Generate realistic MT5 price with FIXED bias feature that works for all currency pairs.
    
    Key Fix: Removed over-complicated normalization that was weakening bias.
    Now uses simple percentage-based bias with pip-aware bounds.
    """
    # Get pip value for this symbol
    pip_value = get_pip_value_for_symbol(symbol)
    
    # ========================================================================
    # VOLATILITY: Normalize to produce similar pip movements across all pairs
    # ========================================================================
    # This ensures EURUSD and USDJPY move similar NUMBER OF PIPS per tick
    normalized_volatility = volatility * (0.0001 / pip_value)
    
    # Random price change with normalized volatility
    change = np.random.normal(0, normalized_volatility)
    
    # Mean reversion (percentage-based, consistent across all pairs)
    price_deviation = (mean_price - previous_price) / previous_price
    reversion = mean_reversion_strength * price_deviation
    
    # ========================================================================
    # BIAS: FIXED VERSION - Simple and effective for all pairs
    # ========================================================================
    if bias_factor > 0:
        # Determine direction toward target
        target_direction = 1 if ((trade_direction == TradeDirection.BUY and target_type == TargetType.PROFIT) or 
                               (trade_direction == TradeDirection.SELL and target_type == TargetType.LOSS)) else -1
        
        # Calculate bias as simple percentage
        price_diff = target_price - previous_price
        bias = bias_factor * price_diff / previous_price
        
        # Cap bias at 0.1% per tick (prevents jumps, allows steady progress)
        # This is 10x stronger than old version's 0.001% cap
        bias = max(min(bias, 0.001), -0.001)
        
        # Apply bias in target direction
        change += target_direction * abs(bias)
    
    # ========================================================================
    # CALCULATE NEW PRICE
    # ========================================================================
    new_price = previous_price * (1 + change + reversion)
    
    # ========================================================================
    # BOUNDS: Pip-aware maximum change per tick
    # ========================================================================
    # Maximum 5 pips per tick for any pair (prevents unrealistic jumps)
    max_pips_per_tick = 5
    max_price_change_pct = (max_pips_per_tick * pip_value) / previous_price
    
    min_price = previous_price * (1 - max_price_change_pct)
    max_price = previous_price * (1 + max_price_change_pct)
    new_price = max(min_price, min(max_price, new_price))
    
    # Ensure price never goes negative or zero
    return max(pip_value, new_price)

# Account-specific trade simulation loop
async def account_simulate_trades(account_type: str):
    """Simulate trades for a specific account"""
    while True:
        await asyncio.sleep(1)
        
        active_trades = active_trades_store[account_type]
        currency_pair_settings = currency_pair_settings_store[account_type]
        account_metrics = account_metrics_store[account_type]
        
        trades_to_remove = []
        
        for trade_id, trade in list(active_trades.items()):
            if trade.status != TradeStatus.RUNNING:
                continue

            symbol_settings = currency_pair_settings.get(trade.symbol)
            if not symbol_settings or (trade.trade_direction == TradeDirection.BUY and not symbol_settings.buy_enabled) or (trade.trade_direction == TradeDirection.SELL and not symbol_settings.sell_enabled):
                trade.status = TradeStatus.STOPPED
                # Store WITHOUT timezone info in database
                trade.end_time = get_current_time_in_timezone().replace(tzinfo=None)
                trade.closing_price = trade.current_buy_price if trade.trade_direction == TradeDirection.BUY else trade.current_sell_price
                account_metrics.balance += trade.profit_loss
                await update_account_metrics(account_type)
                await notify_trade_update(account_type, trade, "disabled")
                
                # Save to database and remove from active trades
                SessionLocal = account_session_makers[account_type]
                with SessionLocal() as db:
                    # Check if trade already exists in database
                    existing_trade = db.query(TradeDataDB).filter(
                        TradeDataDB.trade_id == trade_id
                    ).first()
                    
                    if existing_trade:
                        # Update existing trade
                        for key, value in trade.model_dump().items():
                            setattr(existing_trade, key, value)
                    else:
                        # Insert new trade
                        db_trade = TradeDataDB(**trade.model_dump())
                        db.add(db_trade)
                    
                    db.commit()
                    print(f"✅ Transferred stopped trade {trade_id} to database")
                
                # Mark for removal from active trades
                trades_to_remove.append(trade_id)
                continue

            mean_price = symbol_settings.buy_starting_price
            
            # Store previous price for overshoot detection
            previous_buy_price = trade.current_buy_price
            previous_sell_price = trade.current_sell_price
            
            # Store previous P&L for comparison
            previous_pnl = trade.profit_loss
            
            # Generate new price with proper pip value normalization
            new_buy_price = generate_mt5_price(
                previous_price=trade.current_buy_price,
                volatility=symbol_settings.volatility,
                mean_price=mean_price,
                mean_reversion_strength=symbol_settings.mean_reversion_strength,
                target_price=trade.target_price,
                bias_factor=trade.bias_factor,
                trade_direction=trade.trade_direction,
                target_type=trade.target_type,
                symbol=trade.symbol  # Pass symbol for pip value determination
            )
            new_sell_price = new_buy_price + symbol_settings.spread
            trade.current_buy_price = new_buy_price
            trade.current_sell_price = new_sell_price

            current_time = get_current_time_in_timezone().replace(tzinfo=None)
            seconds_elapsed = (current_time - trade.start_time).total_seconds()
            days_elapsed = seconds_elapsed / (24 * 3600)
            swap_rate = SWAP_RATE_BUY if trade.trade_direction == TradeDirection.BUY else SWAP_RATE_SELL
            trade.swap = swap_rate * trade.lot_size * 100000 * days_elapsed

            current_price = new_buy_price if trade.trade_direction == TradeDirection.BUY else new_sell_price
            trade.calculate_pnl(current_price)

            # Precise target detection with interpolation
            target_reached = False
            reason = ""
            
            if trade.target_type == TargetType.PROFIT:
                # For profit targets, check if we crossed the threshold
                if previous_pnl < trade.target_amount <= trade.profit_loss:
                    target_reached = True
                    reason = "target_profit_reached"
                    
                    # INTERPOLATION: Set exact target P&L to prevent overshoot
                    trade.profit_loss = trade.target_amount
                    
                    # Back-calculate the exact price that gives this P&L
                    if trade.trade_direction == TradeDirection.BUY:
                        if trade.symbol.startswith("USD"):
                            # For USD pairs: profit_loss = (price - entry) * lot * 100000 / price - fees
                            target_price_diff = (trade.target_amount + trade.commission + trade.swap) * trade.entry_price / (trade.lot_size * 100000)
                            trade.current_buy_price = trade.entry_price + target_price_diff
                        else:
                            target_price_diff = (trade.target_amount + trade.commission + trade.swap) / (trade.lot_size * 100000)
                            trade.current_buy_price = trade.entry_price + target_price_diff
                        trade.current_sell_price = trade.current_buy_price + symbol_settings.spread
                    else:  # SELL
                        if trade.symbol.startswith("USD"):
                            target_price_diff = (trade.target_amount + trade.commission + trade.swap) * trade.entry_price / (trade.lot_size * 100000)
                            trade.current_sell_price = trade.entry_price - target_price_diff
                        else:
                            target_price_diff = (trade.target_amount + trade.commission + trade.swap) / (trade.lot_size * 100000)
                            trade.current_sell_price = trade.entry_price - target_price_diff
                        trade.current_buy_price = trade.current_sell_price - symbol_settings.spread
                        
            elif trade.target_type == TargetType.LOSS:
                # For loss targets, check if we crossed the threshold (loss is negative)
                if previous_pnl > -trade.target_amount >= trade.profit_loss:
                    target_reached = True
                    reason = "target_loss_reached"
                    
                    # INTERPOLATION: Set exact target loss
                    trade.profit_loss = -trade.target_amount
                    
                    # Back-calculate exact price
                    if trade.trade_direction == TradeDirection.BUY:
                        if trade.symbol.startswith("USD"):
                            target_price_diff = (-trade.target_amount + trade.commission + trade.swap) * trade.entry_price / (trade.lot_size * 100000)
                            trade.current_buy_price = trade.entry_price + target_price_diff
                        else:
                            target_price_diff = (-trade.target_amount + trade.commission + trade.swap) / (trade.lot_size * 100000)
                            trade.current_buy_price = trade.entry_price + target_price_diff
                        trade.current_sell_price = trade.current_buy_price + symbol_settings.spread
                    else:  # SELL
                        if trade.symbol.startswith("USD"):
                            target_price_diff = (-trade.target_amount + trade.commission + trade.swap) * trade.entry_price / (trade.lot_size * 100000)
                            trade.current_sell_price = trade.entry_price - target_price_diff
                        else:
                            target_price_diff = (-trade.target_amount + trade.commission + trade.swap) / (trade.lot_size * 100000)
                            trade.current_sell_price = trade.entry_price - target_price_diff
                        trade.current_buy_price = trade.current_sell_price - symbol_settings.spread

            if target_reached:
                trade.status = TradeStatus.COMPLETED
                # Store WITHOUT timezone info in database
                trade.end_time = get_current_time_in_timezone().replace(tzinfo=None)
                trade.closing_price = trade.current_buy_price if trade.trade_direction == TradeDirection.BUY else trade.current_sell_price
                account_metrics.balance += trade.profit_loss
                await notify_trade_update(account_type, trade, reason)
                
                # Immediately transfer the completed trade to the database
                await transfer_completed_trades_to_database(account_type, trade_id)
                # Mark for removal from active trades
                trades_to_remove.append(trade_id)
            else:
                await broadcast_trade_update(account_type, trade)

        await update_account_metrics(account_type)

        # Remove completed/stopped trades from active_trades
        for tid in trades_to_remove:
            if tid in active_trades:
                del active_trades[tid]
                print(f"✅ Removed trade {tid} from active trades")

async def transfer_completed_trades_to_database(account_type: str, trade_id: str = None):
    """Transfer completed or stopped trades from active_trades to database
    
    Args:
        account_type: The account type (VIP, DEMO, PRO, MONEY)
        trade_id: Optional specific trade ID to process. If None, processes all completed/stopped trades.
    """
    try:
        active_trades = active_trades_store[account_type]
        SessionLocal = account_session_makers[account_type]
        completed_trades = []
        
        # If specific trade_id is provided, only process that trade
        if trade_id is not None:
            trade = active_trades.get(trade_id)
            if trade and trade.status in [TradeStatus.COMPLETED, TradeStatus.STOPPED]:
                completed_trades = [(trade_id, trade)]
        else:
            # Otherwise, process all completed/stopped trades
            for tid, t in list(active_trades.items()):
                if t.status in [TradeStatus.COMPLETED, TradeStatus.STOPPED]:
                    completed_trades.append((tid, t))
        
        if not completed_trades:
            return
        
        trades_to_remove = []
        
        # Process completed trades in a single transaction
        with SessionLocal() as db:
            for tid, trade in completed_trades:
                try:
                    # Check if trade exists in database
                    existing_trade = db.query(TradeDataDB).filter(
                        TradeDataDB.trade_id == tid
                    ).first()
                    
                    if existing_trade:
                        # Only update if the trade is still RUNNING in database
                        if existing_trade.status == TradeStatus.RUNNING:
                            for key, value in trade.model_dump().items():
                                setattr(existing_trade, key, value)
                    else:
                        # Insert new trade
                        db_trade = TradeDataDB(**trade.model_dump())
                        db.add(db_trade)
                    
                    trades_to_remove.append(tid)
                    
                except Exception as e:
                    print(f"⚠️ Error processing trade {tid}: {e}")
                    continue  # Continue with next trade even if one fails
            
            try:
                db.commit()
                
                # Remove from active trades after successful DB commit
                for tid in trades_to_remove:
                    if tid in active_trades:
                        del active_trades[tid]
                        print(f"✅ Removed trade {tid} from active trades after DB commit")
                
                if trades_to_remove:
                    print(f"✅ Transferred {len(trades_to_remove)} completed/stopped trades to database for {account_type} account")
                    
            except Exception as commit_error:
                db.rollback()
                print(f"⚠️ Database commit failed: {commit_error}")
                return  # Don't remove trades if commit failed
            
    except Exception as e:
        print(f"⚠️ Error transferring completed trades to database for {account_type}: {e}")
        # If there was an error, try to clean up any trades that were already processed
        if 'trades_to_remove' in locals():
            for tid in trades_to_remove:
                if tid in active_trades and active_trades[tid].status in [TradeStatus.COMPLETED, TradeStatus.STOPPED]:
                    del active_trades[tid]
                    print(f"✅ Removed trade {tid} from active trades after error")

async def auto_save_trades_to_database(account_type: str):
    """Automatically save active trade snapshots to database every 30 seconds"""
    while True:
        await asyncio.sleep(30)  # Update every 30 seconds
        
        try:
            # First, transfer any completed/stopped trades to database
            await transfer_completed_trades_to_database(account_type)
            
            # Then update active trades
            active_trades = active_trades_store[account_type]
            SessionLocal = account_session_makers[account_type]
            
            with SessionLocal() as db:
                # Update or insert all RUNNING trades in the database
                for trade_id, trade in active_trades.items():
                    # Skip non-running trades (should be handled by transfer_completed_trades_to_database)
                    if trade.status != TradeStatus.RUNNING:
                        continue
                        
                    # Check if trade exists in database
                    existing_trade = db.query(TradeDataDB).filter(
                        TradeDataDB.trade_id == trade_id
                    ).first()
                    
                    if existing_trade:
                        # For existing trades, only update if they're RUNNING in database
                        if existing_trade.status == TradeStatus.RUNNING:
                            for key, value in trade.model_dump().items():
                                setattr(existing_trade, key, value)
                    else:
                        # Insert new trade snapshot
                        db_trade = TradeDataDB(**trade.model_dump())
                        db.add(db_trade)
                
                db.commit()
                print(f"✅ Auto-saved {len(active_trades)} active trades for {account_type} account")
        except Exception as e:
            print(f"⚠️ Error auto-saving trades for {account_type}: {e}")

async def update_account_metrics(account_type: str):
    active_trades = active_trades_store[account_type]
    account_metrics = account_metrics_store[account_type]
    
    total_margin = 0.0
    total_pnl = 0.0
    total_swap = 0.0
    
    for trade in active_trades.values():
        if trade.status == TradeStatus.RUNNING:
            contract_size = 100000
            price = trade.current_buy_price if trade.trade_direction == TradeDirection.BUY else trade.current_sell_price
            if trade.symbol.startswith("USD"):
                trade.margin_used = (trade.lot_size * contract_size) / DEFAULT_LEVERAGE
            else:
                trade.margin_used = (trade.lot_size * contract_size * price) / DEFAULT_LEVERAGE
            total_margin += trade.margin_used
            total_pnl += trade.profit_loss
            total_swap += trade.swap
    
    account_metrics.margin = total_margin
    account_metrics.equity = account_metrics.balance + total_pnl
    account_metrics.free_margin = max(0, account_metrics.equity - account_metrics.margin)
    account_metrics.margin_level = (account_metrics.equity / account_metrics.margin * 100) if account_metrics.margin > 0 else 0
    account_metrics.profit = total_pnl
    account_metrics.total_swap = total_swap
    account_metrics.total_profit_loss = total_pnl

async def notify_trade_update(account_type: str, trade: TradeData, reason: str):
    await broadcast_trade_update(account_type, trade)
    current_time = get_current_time_in_timezone()
    await manager.broadcast(json.dumps({
        "type": f"trade_{trade.status.lower()}",
        "account_type": account_type,
        "trade_id": trade.trade_id,
        "reason": reason,
        "timestamp": current_time.strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")
    }))

async def broadcast_trade_update(account_type: str, trade: TradeData):
    account_metrics = account_metrics_store[account_type]
    current_time = get_current_time_in_timezone()
    await manager.broadcast(json.dumps({
        "type": "price_update",
        "account_type": account_type,
        "trade_id": trade.trade_id,
        "symbol": trade.symbol,
        "bid": trade.current_buy_price,
        "ask": trade.current_sell_price,
        "profit": trade.profit_loss,
        "direction": trade.trade_direction,
        "status": trade.status,
        "target_type": trade.target_type,
        "target_amount": trade.target_amount,
        "timestamp": current_time.strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}"),
        "account_metrics": account_metrics.model_dump()
    }))

# API Endpoints
@app.get("/", response_class=HTMLResponse)
async def root():
    accounts_html = "".join([f'<li><strong>{acc}:</strong> Balance = ${account_metrics_store[acc].balance:,.2f}, Active Trades = {len(active_trades_store[acc])}</li>' for acc in ACCOUNT_TYPES])
    
    return f"""
    <html>
        <head>
            <title>MetaTrader 5 Simulator API - Multi-Account</title>
            <style>
                body {{ font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }}
                h1 {{ color: #2c3e50; }}
                h3 {{ color: #34495e; }}
                ul {{ background: white; padding: 20px; border-radius: 8px; }}
                li {{ margin: 10px 0; }}
            </style>
        </head>
        <body>
            <h1>🚀 MetaTrader 5 Simulator API - Multi-Account Edition</h1>
            <p>🕒 <strong>Current Timezone:</strong> {SYSTEM_TIMEZONE}</p>
            
            <h3>💼 Available Accounts:</h3>
            <ul>
                {accounts_html}
            </ul>
            
            <h3>📊 Account Overview:</h3>
            <ul>
                <li><strong>Current Account:</strong> {session_state.get("current_account", "VIP")}</li>
            </ul>

            <p><strong>📖 Full API Documentation:</strong> <a href="/docs">/docs</a></p>
        </body>
    </html>
    """

@app.post("/token", response_model=Token)
async def login_for_access_token(form_data: OAuth2PasswordRequestForm = Depends()):
    return Token()

class AccountBalancesRequest(BaseModel):
    accounts: Dict[str, float]

@app.post("/api/accounts/initialize")
async def initialize_accounts(request: AccountBalancesRequest):
    """Initialize all account balances from pop.py"""
    for account_type, balance in request.accounts.items():
        if account_type in ACCOUNT_TYPES:
            account_metrics_store[account_type] = AccountMetrics(
                balance=balance, equity=balance, free_margin=balance
            )
    
    print(f"✅ Initialized accounts: {request.accounts}")
    
    return {
        "status": "success",
        "message": "All accounts initialized successfully",
        "accounts": request.accounts
    }

class SwitchAccountRequest(BaseModel):
    account_type: str

@app.post("/api/switch-account")
async def switch_account(request: SwitchAccountRequest):
    """Switch to a different account"""
    if request.account_type not in ACCOUNT_TYPES:
        raise HTTPException(status_code=400, detail=f"Invalid account type. Must be one of: {ACCOUNT_TYPES}")
    
    old_account = session_state["current_account"]
    session_state["current_account"] = request.account_type
    session_state["last_switch_time"] = datetime.now(timezone.utc)
    
    await manager.broadcast(json.dumps({
        "type": "account_switched",
        "old_account": old_account,
        "new_account": request.account_type,
        "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")
    }))
    
    print(f"Account switched from {old_account} to {request.account_type}")
    
    return {
        "status": "success",
        "message": f"Switched to {request.account_type} account",
        "old_account": old_account,
        "new_account": request.account_type,
        "account_metrics": account_metrics_store[request.account_type].model_dump()
    }

@app.get("/api/accounts/list")
async def list_accounts():
    """Get list of all accounts with their metrics"""
    accounts_info = {}
    for account_type in ACCOUNT_TYPES:
        active_trades = active_trades_store[account_type]
        metrics = account_metrics_store[account_type]
        accounts_info[account_type] = {
            "balance": metrics.balance,
            "equity": metrics.equity,
            "active_trades": len(active_trades),
            "margin": metrics.margin,
            "free_margin": metrics.free_margin
        }
    
    return {
        "accounts": accounts_info,
        "current_account": session_state.get("current_account", "VIP"),
        "message": "All accounts retrieved successfully"
    }

@app.get("/api/users/info", response_model=UserInfo)
async def get_user_info():
    return UserInfo(
        role=UserRole.USER,
        username="public_user",
        is_first_admin=False,
        admin_occupied=False,
        admin_login_time=None,
        current_account=session_state.get("current_account", "VIP")
    )

class TimezoneConfig(BaseModel):
    timezone: str = Field(..., description="Timezone identifier")

@app.get("/api/timezone")
async def get_timezone():
    return {
        "timezone": SYSTEM_TIMEZONE,
        "current_time": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}"),
        "message": "Current timezone configuration"
    }

@app.put("/api/timezone")
async def set_timezone(config: TimezoneConfig):
    global SYSTEM_TIMEZONE
    
    try:
        pytz.timezone(config.timezone)
        old_timezone = SYSTEM_TIMEZONE
        SYSTEM_TIMEZONE = config.timezone
        
        print(f"Timezone changed from {old_timezone} to {SYSTEM_TIMEZONE}")
        
        await manager.broadcast(json.dumps({
            "type": "timezone_changed",
            "old_timezone": old_timezone,
            "new_timezone": SYSTEM_TIMEZONE,
            "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")
        }))
        
        return {
            "status": "success",
            "message": f"Timezone changed to {SYSTEM_TIMEZONE}",
            "old_timezone": old_timezone,
            "new_timezone": SYSTEM_TIMEZONE,
            "current_time": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")
        }
    except pytz.exceptions.UnknownTimeZoneError:
        raise HTTPException(status_code=400, detail=f"Invalid timezone: {config.timezone}")

@app.get("/api/timezones/list")
async def list_timezones():
    common_timezones = [
        "UTC", "America/New_York", "America/Chicago", "America/Los_Angeles",
        "Europe/London", "Europe/Paris", "Europe/Berlin", "Asia/Tokyo",
        "Asia/Shanghai", "Asia/Dubai", "Australia/Sydney", "Africa/Johannesburg"
    ]
    return {
        "common_timezones": common_timezones,
        "all_timezones": pytz.all_timezones,
        "current_timezone": SYSTEM_TIMEZONE
    }

class ResponseModel(BaseModel):
    data: dict
    message: str = ""
    success: bool = True

@app.get("/api/currency-pairs", response_model=ResponseModel)
async def get_currency_pairs():
    account_type = session_state.get("current_account", "VIP")
    currency_pair_settings = currency_pair_settings_store[account_type]
    return ResponseModel(
        data={k: v.model_dump() for k, v in currency_pair_settings.items()},
        message=f"Currency pairs retrieved for {account_type} account"
    )

@app.get("/api/account-metrics", response_model=AccountMetrics)
async def get_account_metrics():
    account_type = session_state.get("current_account", "VIP")
    await update_account_metrics(account_type)
    return account_metrics_store[account_type]

@app.get("/api/trades/active", response_model=List[TradeData])
async def list_active_trades(status: Optional[TradeStatus] = None, symbol: Optional[str] = None):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    trades = list(active_trades.values())
    if status:
        trades = [t for t in trades if t.status == status]
    if symbol:
        trades = [t for t in trades if t.symbol == symbol]
    # Sort by start_time ascending (oldest first)
    trades.sort(key=lambda t: t.start_time, reverse=False)
    return trades

@app.get("/api/trades/historical", response_model=List[TradeData])
async def list_historical_trades(symbol: Optional[str] = None):
    account_type = session_state.get("current_account", "VIP")
    SessionLocal = account_session_makers[account_type]
    
    with SessionLocal() as db:
        # CRITICAL: Only return COMPLETED or STOPPED trades (historical trades only)
        query = db.query(TradeDataDB).filter(
            TradeDataDB.status.in_([TradeStatus.COMPLETED, TradeStatus.STOPPED])
        )
        if symbol:
            query = query.filter(TradeDataDB.symbol == symbol)
        # Sort by end_time descending (most recently closed first), then by start_time descending
        # Nulls are placed last in SQLite by default with desc()
        query = query.order_by(TradeDataDB.end_time.desc(), TradeDataDB.start_time.desc())
        trades = query.all()
        return [TradeData(**{k: getattr(t, k) for k in TradeData.model_fields}) for t in trades]

@app.get("/api/trades/{trade_id}", response_model=TradeData)
async def get_trade(trade_id: str):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    if trade_id in active_trades:
        return active_trades[trade_id]
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        trade = db.query(TradeDataDB).filter(TradeDataDB.trade_id == trade_id).first()
        if trade:
            return TradeData(**{k: getattr(trade, k) for k in TradeData.model_fields})
    raise HTTPException(status_code=404, detail="Trade not found")

@app.get("/api/trades/{trade_id}/details")
async def get_trade_details(trade_id: str):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    if trade_id in active_trades:
        trade = active_trades[trade_id]
        return {"trade": trade, "is_active": True, "account_type": account_type, "message": "Active trade details retrieved"}
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        trade = db.query(TradeDataDB).filter(TradeDataDB.trade_id == trade_id).first()
        if trade:
            return {"trade": TradeData(**{k: getattr(trade, k) for k in TradeData.model_fields}), "is_active": False, "account_type": account_type, "message": "Historical trade details retrieved"}
    raise HTTPException(status_code=404, detail="Trade not found")

@app.get("/api/summary/trades")
async def get_trades_summary():
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    account_metrics = account_metrics_store[account_type]
    
    active_trade_list = list(active_trades.values())
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        # Sort historical trades by end_time descending (most recently closed first)
        # CRITICAL: Only get COMPLETED or STOPPED trades
        historical_trades_db = db.query(TradeDataDB).filter(
            TradeDataDB.status.in_([TradeStatus.COMPLETED, TradeStatus.STOPPED])
        ).order_by(TradeDataDB.end_time.desc(), TradeDataDB.start_time.desc()).all()
        historical_trade_list = [TradeData(**{k: getattr(t, k) for k in TradeData.model_fields}) for t in historical_trades_db]
    
    # Sort active trades by start_time ascending (oldest first)
    active_trade_list.sort(key=lambda t: t.start_time, reverse=False)
    
    all_trades = active_trade_list + historical_trade_list
    
    total_trades = len(all_trades)
    running_trades = len([t for t in all_trades if t.status == TradeStatus.RUNNING])
    completed_trades = len([t for t in all_trades if t.status == TradeStatus.COMPLETED])
    stopped_trades = len([t for t in all_trades if t.status == TradeStatus.STOPPED])
    
    completed_profits = [t.profit_loss for t in all_trades if t.status in [TradeStatus.COMPLETED, TradeStatus.STOPPED]]
    total_realized_pnl = sum(completed_profits) if completed_profits else 0.0
    current_unrealized_pnl = sum([t.profit_loss for t in active_trade_list])
    
    profitable_trades = len([p for p in completed_profits if p > 0])
    losing_trades = len([p for p in completed_profits if p < 0])
    win_rate = (profitable_trades / len(completed_profits) * 100) if completed_profits else 0.0
    total_swap_all = sum([t.swap for t in all_trades])
    
    return {
        "account_type": account_type,
        "trades_summary": {
            "total_trades": total_trades,
            "running_trades": running_trades,
            "completed_trades": completed_trades,
            "stopped_trades": stopped_trades,
            "win_rate_percentage": round(win_rate, 2),
            "profitable_trades": profitable_trades,
            "losing_trades": losing_trades
        },
        "financial_summary": {
            "total_realized_pnl": round(total_realized_pnl, 2),
            "current_unrealized_pnl": round(current_unrealized_pnl, 2),
            "total_swap_fees": round(total_swap_all, 2),
            "account_balance": round(account_metrics.balance, 2),
            "account_equity": round(account_metrics.equity, 2),
            "free_margin": round(account_metrics.free_margin, 2),
            "margin_level_percentage": round(account_metrics.margin_level, 2)
        },
        "all_trades": all_trades,
        "message": f"Retrieved {total_trades} total trades from {account_type} account"
    }

@app.get("/api/summary/account")
async def get_account_summary():
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    account_metrics = account_metrics_store[account_type]
    
    await update_account_metrics(account_type)
    active_trade_count = len(active_trades)
    total_lot_size = sum([t.lot_size for t in active_trades.values()])
    
    return {
        "account_type": account_type,
        "account_metrics": account_metrics.model_dump(),
        "trading_summary": {
            "active_trades_count": active_trade_count,
            "total_lot_size_active": round(total_lot_size, 2),
            "margin_utilization_percentage": round((account_metrics.margin / account_metrics.equity * 100) if account_metrics.equity > 0 else 0, 2)
        },
        "message": f"Account summary retrieved for {account_type}"
    }

class UpdateTradeRequest(BaseModel):
    model_config = ConfigDict(json_encoders={datetime: lambda v: v.isoformat()})
    
    entry_price: Optional[float] = Field(None, description="New entry price")
    closing_price: Optional[float] = Field(None, description="New closing price")
    start_time: Optional[datetime] = Field(None, description="New start time (timezone-naive)")
    end_time: Optional[datetime] = Field(None, description="New end time (timezone-naive)")
    lot_size: Optional[float] = Field(None, description="New lot size")
    profit_loss: Optional[float] = Field(None, description="Manual profit/loss override")
    status: Optional[TradeStatus] = Field(None, description="Updated trade status")

@app.put("/api/trades/{trade_id}/update")
async def update_historical_trade(trade_id: str, request: UpdateTradeRequest):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    if trade_id in active_trades:
        raise HTTPException(status_code=400, detail="Cannot update active trade. Close the trade first.")
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        trade_db = db.query(TradeDataDB).filter(TradeDataDB.trade_id == trade_id).first()
        if not trade_db:
            raise HTTPException(status_code=404, detail="Trade not found in database")
        
        updates = []
        
        if request.entry_price is not None and request.entry_price > 0:
            old_value = trade_db.entry_price
            trade_db.entry_price = request.entry_price
            updates.append(f"entry_price: {old_value:.5f} → {request.entry_price:.5f}")
        
        if request.closing_price is not None and request.closing_price > 0:
            old_value = trade_db.closing_price
            old_str = f"{old_value:.5f}" if old_value is not None else "None"
            trade_db.closing_price = request.closing_price
            updates.append(f"closing_price: {old_str} → {request.closing_price:.5f}")
        
        # Allow admin to update start_time (stored WITHOUT timezone)
        if request.start_time is not None:
            old_value = trade_db.start_time
            if request.start_time.tzinfo:
                trade_db.start_time = request.start_time.replace(tzinfo=None)
            else:
                trade_db.start_time = request.start_time
            updates.append(f"start_time: {old_value} → {trade_db.start_time}")
        
        # Allow admin to update end_time (stored WITHOUT timezone)
        # CRITICAL: Allow admin to update end_time (stored WITHOUT timezone)
        # But prevent clearing end_time if it was already set
        if request.end_time is not None:
            old_value = trade_db.end_time
            old_str = str(old_value) if old_value is not None else "None"
            if request.end_time.tzinfo:
                trade_db.end_time = request.end_time.replace(tzinfo=None)
            else:
                trade_db.end_time = request.end_time
            updates.append(f"end_time: {old_str} → {trade_db.end_time}")
        elif trade_db.end_time is None and trade_db.status in [TradeStatus.COMPLETED, TradeStatus.STOPPED]:
            # If end_time is missing but trade is completed, set it to start_time as fallback
            trade_db.end_time = trade_db.start_time
            updates.append(f"end_time: None → {trade_db.end_time} (auto-fixed)")
        
        if request.lot_size is not None and request.lot_size > 0:
            old_value = trade_db.lot_size
            trade_db.lot_size = request.lot_size
            updates.append(f"lot_size: {old_value:.2f} → {request.lot_size:.2f}")
        
        if request.status is not None:
            old_value = trade_db.status
            trade_db.status = request.status
            if old_value != trade_db.status:
                updates.append(f"status: {old_value.value if isinstance(old_value, TradeStatus) else old_value} → {trade_db.status.value if isinstance(trade_db.status, TradeStatus) else trade_db.status}")
        else:
            if (trade_db.end_time is not None or trade_db.closing_price is not None) and trade_db.status != TradeStatus.COMPLETED:
                old_value = trade_db.status
                trade_db.status = TradeStatus.COMPLETED
                updates.append(f"status: {old_value.value if isinstance(old_value, TradeStatus) else old_value} → {trade_db.status.value}")
        
        # Allow admin to manually update profit_loss
        if request.profit_loss is not None:
            old_value = trade_db.profit_loss
            trade_db.profit_loss = request.profit_loss
            updates.append(f"profit_loss: {old_value:.2f} → {request.profit_loss:.2f}")
        
        db.commit()
        db.refresh(trade_db)
        
        updated_trade = TradeData(**{k: getattr(trade_db, k) for k in TradeData.model_fields})
        
        print(f"Trade {trade_id} updated in {account_type}: {', '.join(updates)}")
        
        await manager.broadcast(json.dumps({
            "type": "trade_database_updated",
            "account_type": account_type,
            "trade_id": trade_id,
            "updates": updates,
            "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")
        }))
        
        return {"status": "success", "message": f"Trade {trade_id} updated successfully in {account_type}", "updates_applied": updates, "trade": updated_trade}

@app.post("/api/trades/{trade_id}/recalculate")
async def recalculate_trade_pnl(trade_id: str):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    if trade_id in active_trades:
        raise HTTPException(status_code=400, detail="Cannot recalculate active trade")
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        trade_db = db.query(TradeDataDB).filter(TradeDataDB.trade_id == trade_id).first()
        if not trade_db:
            raise HTTPException(status_code=404, detail="Trade not found")
        
        if trade_db.closing_price is None:
            raise HTTPException(status_code=400, detail="Closing price must be set before recalculation")
        
        if trade_db.trade_direction == TradeDirection.BUY:
            price_diff = trade_db.closing_price - trade_db.entry_price
        else:
            price_diff = trade_db.entry_price - trade_db.closing_price
        
        calculated_pnl = price_diff * trade_db.lot_size * 100000
        
        if trade_db.symbol.startswith("USD"):
            calculated_pnl /= trade_db.closing_price
        
        calculated_pnl -= (trade_db.commission + trade_db.swap)
        
        old_pnl = trade_db.profit_loss
        trade_db.profit_loss = calculated_pnl
        
        db.commit()
        db.refresh(trade_db)
        
        print(f"Trade {trade_id} recalculated in {account_type}: P&L {old_pnl:.2f} → {calculated_pnl:.2f}")
        
        updated_trade = TradeData(**{k: getattr(trade_db, k) for k in TradeData.model_fields})
        await manager.broadcast(json.dumps({
            "type": "trade_database_updated",
            "account_type": account_type,
            "trade_id": trade_id,
            "updates": ["profit_loss"],
            "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}"),
            "trade": updated_trade.model_dump()
        }))
        
        return {"status": "success", "message": "P&L recalculated successfully", "account_type": account_type, "old_pnl": round(old_pnl, 2), "new_pnl": round(calculated_pnl, 2), "trade_id": trade_id, "trade": updated_trade}

@app.delete("/api/trades/{trade_id}")
async def delete_trade(trade_id: str):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    if trade_id in active_trades:
        raise HTTPException(status_code=400, detail="Cannot delete active trade. Close it first.")
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        trade_db = db.query(TradeDataDB).filter(TradeDataDB.trade_id == trade_id).first()
        if not trade_db:
            raise HTTPException(status_code=404, detail="Trade not found")
        
        trade_info = {"symbol": trade_db.symbol, "entry_price": trade_db.entry_price, "profit_loss": trade_db.profit_loss}
        
        db.delete(trade_db)
        db.commit()
        
        print(f"Trade {trade_id} deleted from {account_type}: {trade_info}")
        
        await manager.broadcast(json.dumps({"type": "trade_deleted", "account_type": account_type, "trade_id": trade_id, "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")}))
        
        return {"status": "success", "message": f"Trade {trade_id} permanently deleted from {account_type}", "deleted_trade_info": trade_info}

class BulkUpdateItem(BaseModel):
    trade_id: str
    updates: UpdateTradeRequest

class BulkUpdateRequest(BaseModel):
    trades: List[BulkUpdateItem]

@app.post("/api/trades/bulk-update")
async def bulk_update_trades(request: BulkUpdateRequest):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    results = []
    errors = []
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        for item in request.trades:
            try:
                if item.trade_id in active_trades:
                    errors.append({"trade_id": item.trade_id, "error": "Trade is active, cannot update"})
                    continue
                
                trade_db = db.query(TradeDataDB).filter(TradeDataDB.trade_id == item.trade_id).first()
                if not trade_db:
                    errors.append({"trade_id": item.trade_id, "error": "Trade not found"})
                    continue
                
                updates_applied = []
                if item.updates.entry_price is not None and item.updates.entry_price > 0:
                    trade_db.entry_price = item.updates.entry_price
                    updates_applied.append("entry_price")
                if item.updates.closing_price is not None and item.updates.closing_price > 0:
                    trade_db.closing_price = item.updates.closing_price
                    updates_applied.append("closing_price")
                if item.updates.start_time is not None:
                    if item.updates.start_time.tzinfo:
                        trade_db.start_time = item.updates.start_time.replace(tzinfo=None)
                    else:
                        trade_db.start_time = item.updates.start_time
                    updates_applied.append("start_time")
                if item.updates.end_time is not None:
                    if item.updates.end_time.tzinfo:
                        trade_db.end_time = item.updates.end_time.replace(tzinfo=None)
                    else:
                        trade_db.end_time = item.updates.end_time
                    updates_applied.append("end_time")
                elif trade_db.end_time is None and trade_db.status in [TradeStatus.COMPLETED, TradeStatus.STOPPED]:
                    # Auto-fix missing end_time for completed trades
                    trade_db.end_time = trade_db.start_time
                    updates_applied.append("end_time (auto-fixed)")
                if item.updates.lot_size is not None and item.updates.lot_size > 0:
                    trade_db.lot_size = item.updates.lot_size
                    updates_applied.append("lot_size")
                if item.updates.profit_loss is not None:
                    trade_db.profit_loss = item.updates.profit_loss
                    updates_applied.append("profit_loss")
                if item.updates.status is not None:
                    trade_db.status = item.updates.status
                    updates_applied.append("status")
                elif (trade_db.end_time is not None or trade_db.closing_price is not None) and trade_db.status != TradeStatus.COMPLETED:
                    trade_db.status = TradeStatus.COMPLETED
                    updates_applied.append("status")
                
                results.append({"trade_id": item.trade_id, "status": "success", "updates_applied": updates_applied})
                
            except Exception as e:
                errors.append({"trade_id": item.trade_id, "error": str(e)})
        
        db.commit()
    
    print(f"Bulk update performed in {account_type}: {len(results)} succeeded, {len(errors)} failed")
    
    return {"status": "completed", "account_type": account_type, "message": f"Bulk update completed: {len(results)} successful, {len(errors)} failed", "successful_updates": results, "errors": errors}

class LicenseKeyRequest(BaseModel):
    license_key: str

@app.post("/api/verify-license")
async def verify_license(request: LicenseKeyRequest):
    if request.license_key == LICENSE_KEY:
        return {"status": "success", "message": "License key verified successfully", "valid": True}
    else:
        raise HTTPException(status_code=400, detail="Invalid license key")

@app.put("/api/currency-pairs/{symbol}", response_model=CurrencyPairConfig)
async def update_currency_pair(symbol: str, settings: CurrencyPairConfig):
    account_type = session_state.get("current_account", "VIP")
    currency_pair_settings = currency_pair_settings_store[account_type]
    
    if symbol not in currency_pair_settings:
        raise HTTPException(status_code=404, detail=f"Currency pair {symbol} not found")
    currency_pair_settings[symbol] = settings
    print(f"{symbol} settings updated in {account_type}")
    return settings

class SetBalanceRequest(BaseModel):
    balance: float = Field(..., gt=0, description="New account balance")

@app.put("/api/account/balance", response_model=AccountMetrics)
async def set_account_balance(request: SetBalanceRequest):
    account_type = session_state.get("current_account", "VIP")
    account_metrics = account_metrics_store[account_type]
    
    account_metrics.balance = request.balance
    await update_account_metrics(account_type)
    print(f"{account_type} balance set to {request.balance}")
    return account_metrics

@app.post("/api/account/deposit")
async def deposit():
    account_type = session_state.get("current_account", "VIP")
    account_metrics = account_metrics_store[account_type]
    
    account_metrics.balance += 300.0
    await update_account_metrics(account_type)
    await manager.broadcast(json.dumps({"type": "deposit", "account_type": account_type, "amount": 300.0, "new_balance": account_metrics.balance, "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")}))
    print(f"Deposited 300.00 to {account_type}")
    return {"status": "success", "account_type": account_type, "message": "Deposited 300.00", "new_balance": account_metrics.balance}

class StartTradeRequest(BaseModel):
    direction: TradeDirection
    lot_size: Optional[float] = None
    target_type: Optional[TargetType] = None
    target_amount: Optional[float] = Field(None, gt=0, description="Target profit or loss in USD")
    starting_buy_price: Optional[float] = Field(None, gt=0, description="Custom starting buy price for BUY trades")

class UpdateTradeTargetRequest(BaseModel):
    target_type: TargetType
    target_amount: float = Field(..., gt=0, description="Target profit or loss in USD")

@app.post("/api/trades/start/{symbol}")
async def start_trade(symbol: str, request: StartTradeRequest):
    account_type = session_state.get("current_account", "VIP")
    currency_pair_settings = currency_pair_settings_store[account_type]
    active_trades = active_trades_store[account_type]
    account_metrics = account_metrics_store[account_type]
    
    if symbol not in currency_pair_settings:
        raise HTTPException(status_code=404, detail=f"Currency pair {symbol} not found")

    symbol_settings = currency_pair_settings[symbol]
    direction = request.direction

    if (direction == TradeDirection.BUY and not symbol_settings.buy_enabled) or (direction == TradeDirection.SELL and not symbol_settings.sell_enabled):
        raise HTTPException(status_code=400, detail=f"{direction} trading is disabled for {symbol}")

    trade_lot_size = request.lot_size if request.lot_size is not None else (symbol_settings.buy_lot_size if direction == TradeDirection.BUY else symbol_settings.sell_lot_size)
    
    if request.starting_buy_price is not None and direction == TradeDirection.BUY:
        entry_price = request.starting_buy_price
        current_buy_price = request.starting_buy_price
        current_sell_price = request.starting_buy_price + symbol_settings.spread
    else:
        entry_price = symbol_settings.sell_starting_price if direction == TradeDirection.BUY else symbol_settings.buy_starting_price
        current_buy_price = symbol_settings.buy_starting_price
        current_sell_price = symbol_settings.sell_starting_price

    target_type = request.target_type if request.target_type else (TargetType.PROFIT if symbol_settings.default_target_profit > 0 else TargetType.LOSS)
    target_amount = request.target_amount if request.target_amount is not None else (symbol_settings.default_target_profit if target_type == TargetType.PROFIT else symbol_settings.default_target_loss)
    if target_amount <= 0:
        raise HTTPException(status_code=400, detail="Target amount must be positive")

    if symbol.startswith("USD"):
        price_diff = target_amount * entry_price / (trade_lot_size * 100000)
    else:
        price_diff = target_amount / (trade_lot_size * 100000)
    if direction == TradeDirection.BUY:
        target_price = entry_price + price_diff if target_type == TargetType.PROFIT else entry_price - price_diff
    else:
        target_price = entry_price - price_diff if target_type == TargetType.PROFIT else entry_price + price_diff

    if symbol.startswith("USD"):
        required_margin = (trade_lot_size * 100000) / DEFAULT_LEVERAGE
    else:
        required_margin = (trade_lot_size * 100000 * entry_price) / DEFAULT_LEVERAGE
    if account_metrics.free_margin < required_margin:
        raise HTTPException(status_code=400, detail=f"Insufficient margin")

    # Generate trade ID: 10 random digits (URL-safe format)
    # Ensure uniqueness by checking against active trades and database
    max_attempts = 10
    trade_id = None
    SessionLocal = account_session_makers[account_type]
    for attempt in range(max_attempts):
        candidate_id = ''.join([str(random.randint(0, 9)) for _ in range(10)])
        # Check if ID already exists in active trades or database
        if candidate_id not in active_trades:
            with SessionLocal() as db:
                existing = db.query(TradeDataDB).filter(TradeDataDB.trade_id == candidate_id).first()
                if not existing:
                    trade_id = candidate_id
                    break
    
    if trade_id is None:
        # Fallback to timestamp-based ID if all random attempts failed
        trade_id = str(int(datetime.now().timestamp() * 1000))[-10:]
    
    # Store start_time WITHOUT timezone for database compatibility
    current_time = get_current_time_in_timezone().replace(tzinfo=None)
    trade_data = TradeData(trade_id=trade_id, symbol=symbol, entry_price=entry_price, current_buy_price=current_buy_price, current_sell_price=current_sell_price, start_time=current_time, status=TradeStatus.RUNNING, target_price=target_price, target_type=target_type, target_amount=target_amount, lot_size=trade_lot_size, trade_direction=direction, commission=trade_lot_size * COMMISSION * 100000, bias_factor=0.0)
    active_trades[trade_id] = trade_data
    await update_account_metrics(account_type)
    print(f"Trade {trade_id} started in {account_type} at {current_time}")
    return {"status": "success", "account_type": account_type, "trade_id": trade_id, "symbol": symbol, "direction": direction, "message": f"{direction} trade started for {symbol}", "start_time": current_time.strftime("%Y-%m-%d %H:%M:%S")}

@app.put("/api/trades/{trade_id}/update-target")
async def update_trade_target(trade_id: str, request: UpdateTradeTargetRequest):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    if trade_id not in active_trades:
        raise HTTPException(status_code=404, detail="Trade not found")
    trade = active_trades[trade_id]
    if trade.status != TradeStatus.RUNNING:
        raise HTTPException(status_code=400, detail=f"Trade is already {trade.status}")

    target_amount = request.target_amount
    target_type = request.target_type

    if trade.symbol.startswith("USD"):
        price_diff = target_amount * trade.entry_price / (trade.lot_size * 100000)
    else:
        price_diff = target_amount / (trade.lot_size * 100000)
    if trade.trade_direction == TradeDirection.BUY:
        target_price = trade.entry_price + price_diff if target_type == TargetType.PROFIT else trade.entry_price - price_diff
    else:
        target_price = trade.entry_price - price_diff if target_type == TargetType.PROFIT else trade.entry_price + price_diff

    trade.target_price = target_price
    trade.target_type = target_type
    trade.target_amount = target_amount
    await update_account_metrics(account_type)
    print(f"Trade {trade_id} updated in {account_type}")
    return {"status": "success", "account_type": account_type, "trade_id": trade_id, "message": f"Trade updated"}

@app.post("/api/trades/{trade_id}/finish")
async def finish_trade(trade_id: str):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    if trade_id not in active_trades:
        raise HTTPException(status_code=404, detail="Trade not found")
    trade = active_trades[trade_id]
    if trade.status != TradeStatus.RUNNING:
        raise HTTPException(status_code=400, detail=f"Trade is already {trade.status}")
    
    trade.bias_factor = 0.05
    await manager.broadcast(json.dumps({"type": "trade_bias_applied", "account_type": account_type, "trade_id": trade_id, "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")}))
    print(f"Trade {trade_id} biased in {account_type}")
    return {"status": "success", "account_type": account_type, "message": f"Trade biased to reach target faster"}

@app.post("/api/trades/{trade_id}/close")
async def close_trade(trade_id: str):
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    account_metrics = account_metrics_store[account_type]
    
    if trade_id not in active_trades:
        raise HTTPException(status_code=404, detail="Trade not found")
    trade = active_trades[trade_id]
    if trade.status != TradeStatus.RUNNING:
        raise HTTPException(status_code=400, detail=f"Trade is already {trade.status}")
    trade.status = TradeStatus.COMPLETED
    # Store end_time WITHOUT timezone for database
    trade.end_time = get_current_time_in_timezone().replace(tzinfo=None)
    trade.closing_price = trade.current_buy_price if trade.trade_direction == TradeDirection.BUY else trade.current_sell_price
    account_metrics.balance += trade.profit_loss
    await update_account_metrics(account_type)
    await notify_trade_update(account_type, trade, "manually_closed")
    
    # Use the enhanced function to transfer the trade to the database
    await transfer_completed_trades_to_database(account_type, trade_id)
    
    # Remove from active trades if still there (should be removed by transfer_completed_trades_to_database)
    if trade_id in active_trades:
        del active_trades[trade_id]
    print(f"Trade {trade_id} closed in {account_type}")
    return {"status": "success", "account_type": account_type, "message": f"Trade closed successfully"}

@app.post("/api/reset")
async def reset_account():
    account_type = session_state.get("current_account", "VIP")
    active_trades = active_trades_store[account_type]
    
    # Reset to default balance
    new_balance = DEFAULT_ACCOUNT_BALANCE
    
    account_metrics_store[account_type] = AccountMetrics(balance=new_balance, equity=new_balance, free_margin=new_balance)
    active_trades.clear()
    
    SessionLocal = account_session_makers[account_type]
    with SessionLocal() as db:
        db.query(TradeDataDB).delete()
        db.commit()
    
    await manager.broadcast(json.dumps({"type": "account_reset", "account_type": account_type, "message": f"{account_type} account reset", "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")}))
    print(f"{account_type} account reset")
    return {"status": "success", "account_type": account_type, "message": f"{account_type} account reset", "new_balance": new_balance}

@app.websocket("/ws/{client_id}")
async def websocket_endpoint(websocket: WebSocket, client_id: str, token: str = None):
    await websocket.accept()
    
    user_role = UserRole.USER
    is_admin = False
    username = None
    
    await manager.connect(websocket)
    
    await websocket.send_text(json.dumps({"type": "connection_established", "client_id": client_id, "role": user_role, "is_admin": is_admin, "current_account": session_state.get("current_account", "VIP"), "timestamp": get_current_time_in_timezone().strftime(f"%Y-%m-%d %H:%M:%S {SYSTEM_TIMEZONE}")}))
    
    try:
        while True:
            data = await websocket.receive_text()
            try:
                message = json.loads(data)
                if message.get("type") == "ping":
                    await websocket.send_text(json.dumps({"type": "pong"}))
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        manager.disconnect(websocket)

if __name__ == "__main__":
    import uvicorn
    import subprocess
    import sys
    import webbrowser
    import os
    import time
    from threading import Timer
    
    print("\n" + "="*60)
    print("🚀 METATRADER 5 SIMULATOR - MULTI-ACCOUNT EDITION")
    print("="*60)
    
    # Check if pop.py should run
    if len(sys.argv) > 1 and sys.argv[1] == "--skip-pop":
        print("\n⏭️  Skipping pop.py initialization...")
    else:
        print("\n📋 Running account initialization (pop.py)...")
        try:
            subprocess.run([sys.executable, "pop.py"], check=True)
        except subprocess.CalledProcessError:
            print("⚠️  pop.py failed, continuing with default balances...")
        except FileNotFoundError:
            print("⚠️  pop.py not found, continuing with default balances...")
    
    print("\n" + "="*60)
    print("🌐 Starting Multi-Account Trading Server...")
    print("="*60)
    print(f"📊 Dashboard: http://127.0.0.1:8002/")
    print(f"📖 API Docs: http://127.0.0.1:8002/docs")
    print(f"🔐 Login: admin / secret")
    print("\n💼 Available Accounts: VIP, DEMO, PRO, MONEY")
    print("="*60 + "\n")
    
    # Function to open the HTML dashboard after server starts
    def open_dashboard():
        time.sleep(2)  # Wait for server to fully start
        html_file = os.path.join(os.path.dirname(__file__), "frontend.html")
        if os.path.exists(html_file):
            print(f"🌐 Opening dashboard: {html_file}")
            webbrowser.open(f"file:///{html_file}")
        else:
            print(f"⚠️  Dashboard file not found: {html_file}")
    
    # Start timer to open HTML dashboard
    Timer(0.5, open_dashboard).start()
    
    try:
        uvicorn.run(
            app, 
            host="0.0.0.0", 
            port=8002,
            log_level="info",
            access_log=True
        )
    except Exception as e:
        print(f"❌ Server error: {e}")
        print("💡 Try running: pip install -r requirements.txt --upgrade")