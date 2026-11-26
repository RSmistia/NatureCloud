import pandas as pd
from sklearn.preprocessing import PolynomialFeatures
from sklearn.linear_model import LinearRegression

# Load data
df = pd.read_csv("dna_large_memory_metrics.csv")

X = df[["allocated_bytes", "min_length"]]
y = df["consumed_memory_bytes"]

# Polynomial regression
poly = PolynomialFeatures(degree=2)

X_poly = poly.fit_transform(X)

model = LinearRegression()
model.fit(X_poly, y)

# Print the mathematical equation
features = poly.get_feature_names_out(
    ["allocated_bytes", "min_length"]
)

print("f(x) =")

for coef, feature in zip(model.coef_, features):
    print(f"{coef} * {feature}")

print("+", model.intercept_)

def consumed_bytes(a, m):
    return (
        71974.8236086406
        + 7.049843718146969e-07 * a
        - 6.787910281059149e-19 * a * a
        - 3.164300911925921e-10 * a * m
        - 4.979366655531984e-13 * m * m
    )

print(consumed_bytes(375500372728,1000))