export class HttpError extends Error { constructor(public status:number, message='Request refused'){super(message);} }

