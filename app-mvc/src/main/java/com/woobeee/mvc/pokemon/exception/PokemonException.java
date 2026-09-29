package com.woobeee.mvc.pokemon.exception;

import lombok.Getter;

@Getter
public class PokemonException extends RuntimeException {
    private final PokemonErrorCode errorCode;

    public PokemonException(PokemonErrorCode errorCode) {
        super(errorCode.reason());
        this.errorCode = errorCode;
    }

    public PokemonErrorCode errorCode() {
        return errorCode;
    }
}
