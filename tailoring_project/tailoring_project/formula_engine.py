"""Safe arithmetic evaluator for garment measurement formulas."""

from __future__ import annotations

import ast
import operator
from collections.abc import Mapping


class FormulaError(ValueError):
    """Raised when a garment formula is invalid or cannot be evaluated."""


_BINARY_OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
}
_UNARY_OPERATORS = {ast.UAdd: operator.pos, ast.USub: operator.neg}
_FUNCTIONS = {"MIN": min, "MAX": max, "ROUND": round}


def parse_formula(expression: str) -> ast.Expression:
    if not expression or not expression.strip():
        raise FormulaError("Formula is required")
    try:
        parsed = ast.parse(expression.strip(), mode="eval")
    except SyntaxError as exc:
        raise FormulaError("Formula syntax is invalid") from exc
    _validate_node(parsed)
    return parsed


def get_references(expression: str) -> set[str]:
    parsed = parse_formula(expression)
    return {
        node.id.upper()
        for node in ast.walk(parsed)
        if isinstance(node, ast.Name) and node.id.upper() not in _FUNCTIONS
    }


def evaluate_formula(expression: str, measurements: Mapping[str, float]) -> float:
    parsed = parse_formula(expression)
    values = {str(key).upper(): float(value) for key, value in measurements.items()}
    return float(_evaluate_node(parsed.body, values))


def _validate_node(node: ast.AST) -> None:
    if isinstance(node, ast.Expression):
        _validate_node(node.body)
        return
    if isinstance(node, ast.Constant):
        if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
            raise FormulaError("Only numeric constants are allowed")
        return
    if isinstance(node, ast.Name):
        if not node.id.replace("_", "").isalnum() or not node.id[0].isalpha():
            raise FormulaError("Measurement code is invalid")
        return
    if isinstance(node, ast.BinOp) and type(node.op) in _BINARY_OPERATORS:
        _validate_node(node.left)
        _validate_node(node.right)
        return
    if isinstance(node, ast.UnaryOp) and type(node.op) in _UNARY_OPERATORS:
        _validate_node(node.operand)
        return
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
        function_name = node.func.id.upper()
        if function_name not in _FUNCTIONS or node.keywords:
            raise FormulaError("Only MIN, MAX, and ROUND functions are allowed")
        for argument in node.args:
            _validate_node(argument)
        return
    raise FormulaError(f"Operation {type(node).__name__} is not allowed")


def _evaluate_node(node: ast.AST, values: Mapping[str, float]) -> float:
    if isinstance(node, ast.Constant):
        return float(node.value)
    if isinstance(node, ast.Name):
        code = node.id.upper()
        if code not in values:
            raise FormulaError(f"Required measurement {code} is missing")
        return values[code]
    if isinstance(node, ast.BinOp):
        left = _evaluate_node(node.left, values)
        right = _evaluate_node(node.right, values)
        try:
            return _BINARY_OPERATORS[type(node.op)](left, right)
        except ZeroDivisionError as exc:
            raise FormulaError("Formula cannot divide by zero") from exc
    if isinstance(node, ast.UnaryOp):
        return _UNARY_OPERATORS[type(node.op)](_evaluate_node(node.operand, values))
    if isinstance(node, ast.Call):
        function_name = node.func.id.upper()
        arguments = [_evaluate_node(argument, values) for argument in node.args]
        try:
            return _FUNCTIONS[function_name](*arguments)
        except (TypeError, ValueError) as exc:
            raise FormulaError(f"Invalid arguments for {function_name}") from exc
    raise FormulaError("Formula contains an unsupported operation")
